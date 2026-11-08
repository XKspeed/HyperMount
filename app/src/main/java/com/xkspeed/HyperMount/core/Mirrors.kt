package com.xkspeed.HyperMount.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 镜像站候选池。
 *
 * 这类站点的生命周期很短（今天能用、明天可能就挂），所以这里只当"候选"用：
 * 每次检查更新 / 下载前实测一轮，谁先回用谁，绝不把某个地址写死成唯一依赖。
 *
 * 改写方式分两类，不能混用：
 *  - [MirrorKind.PREFIX]  原始地址原样拼在镜像前缀后面
 *  - [MirrorKind.REPLACE] 把 github.com 换成镜像域名（这类站复刻的是网页路径）
 */
internal enum class MirrorKind { PREFIX, REPLACE }

internal class Mirror(
    val label: String,
    val base: String,
    val kind: MirrorKind,
) {

    /** 把一个 GitHub 地址改写成该镜像的地址；这条改写不了就返回 null */
    fun wrap(url: String): String? = when (kind) {
        MirrorKind.PREFIX -> if (url.startsWith(GITHUB) || url.startsWith(RAW)) base + url else null
        MirrorKind.REPLACE -> replaceHost(url)
    }

    private fun replaceHost(url: String): String? {
        // 这类站点走的是网页路径：github.com/owner/repo/raw/main/x.json
        // raw.githubusercontent.com 的地址要先换算成同样形状再换域名
        if (url.startsWith(RAW)) {
            val parts = url.removePrefix(RAW).split("/", limit = 3)
            if (parts.size < 3) return null
            return "$base/${parts[0]}/${parts[1]}/raw/${parts[2]}"
        }
        if (url.startsWith(GITHUB)) return base + url.removePrefix(GITHUB)
        return null
    }

    private companion object {
        const val GITHUB = "https://github.com/"
        const val RAW = "https://raw.githubusercontent.com/"
    }
}

/**
 * 并发探测。
 *
 * 全部候选同时发一个只读 1 字节的请求，按「谁先回来」排序。
 * 不用 HEAD：大部分镜像没实现 HEAD，会被误判成死链。
 */
internal object Mirrors {

    /** 只做前缀拼接的加速站：raw 清单与 releases/download 大文件都能转发 */
    private val PREFIX = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://gh.llkk.cc/",
        "https://gh.zwy.one/",
        "https://ghproxy.homeboyc.cn/",
        "https://ghfile.geekertao.top/",
        "https://gh.h233.eu.org/",
        "https://cdn.crashmc.com/",
        "https://github.moeyy.xyz/",
    )

    /** 域名替换型：适合取清单，不适合下大文件 */
    private val REPLACE = listOf(
        "https://bgithub.xyz",
        "https://github.ur1.fun",
    )

    val pool: List<Mirror> = buildList {
        PREFIX.forEach { add(Mirror(it.hostLabel(), it, MirrorKind.PREFIX)) }
        REPLACE.forEach { add(Mirror(it.hostLabel(), it, MirrorKind.REPLACE)) }
    }

    /** 清单候选池：直连 + 全部镜像 + jsdelivr + gitmirror */
    fun manifestVariants(raw: String): List<Pair<Mirror?, String>> = buildList {
        add(null to raw)
        pool.forEach { m -> m.wrap(raw)?.let { add(m to it) } }
        // jsdelivr 的路径格式是 gh/owner/repo@branch/path，单独处理
        jsdelivrManifest(raw)?.let { add(JS_DELIVR to it) }
        add(null to raw.replace("https://raw.githubusercontent.com/", "https://raw.gitmirror.com/"))
    }

    /** 下载候选池：只用能转发 release 资产的镜像 */
    fun fileVariants(raw: String): List<Pair<Mirror?, String>> = buildList {
        add(null to raw)
        pool.forEach { m -> m.wrap(raw)?.let { add(m to it) } }
    }

    /**
     * 并发探测并按完成先后排好。
     * 返回的列表里只剩"地址活着"的那些，最快的一条在最前。
     */
    suspend fun rank(
        variants: List<Pair<Mirror?, String>>,
        timeoutMs: Long,
    ): List<Pair<Mirror?, String>> = coroutineScope {
        variants.map { v ->
            async(Dispatchers.IO) {
                val alive = probe(v.second, timeoutMs).ok
                // 记录完成顺序：先回来的排前面
                Triple(v, alive, if (alive) System.nanoTime() else Long.MAX_VALUE)
            }
        }.awaitAll()
            .filter { it.second }
            .sortedBy { it.third }
            .map { it.first }
    }

    /**
     * 探一条地址：GET + Range 0-0，只读一个字节。
     *
     * 返回 206 说明支持分片（下载分段要用），200 说明地址活着但可能不支持 Range。
     * 两个都算"能用"，具体要不要分段由调用方判断。
     */
    fun probe(url: String, timeoutMs: Long): ProbeResult = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs.toInt()
            readTimeout = timeoutMs.toInt()
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=0-0")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("User-Agent", "HyperMount")
            // 别让 CDN 回一份缓存的旧文件
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            when (val code = conn.responseCode) {
                206 -> ProbeResult(
                    ok = true,
                    supportsRange = true,
                    // Content-Range: bytes 0-0/3693226
                    size = conn.getHeaderField("Content-Range").orEmpty()
                        .substringAfter('/', "0").trim().toLongOrNull() ?: 0L,
                )
                200 -> ProbeResult(true, false, conn.contentLengthLong)
                else -> ProbeResult(false, false, 0L)
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrDefault(ProbeResult(false, false, 0L))

    /** 只判断能不能连上（清单探测用） */
    fun reachable(url: String, timeoutMs: Long): Boolean = probe(url, timeoutMs).ok

    /** 文件大小：Range 0-0 的 Content-Range 形如 `bytes 0-0/3693226` */
    fun sizeOf(url: String, timeoutMs: Long): Long = probe(url, timeoutMs).size

    class ProbeResult(
        val ok: Boolean,
        val supportsRange: Boolean,
        val size: Long,
    )

    private const val JS_DELIVR_HOST = "https://cdn.jsdelivr.net"
    private val JS_DELIVR = Mirror("jsdelivr", JS_DELIVR_HOST, MirrorKind.REPLACE)

    /** cdn.jsdelivr.net/gh/owner/repo@branch/path */
    private fun jsdelivrManifest(raw: String): String? {
        val prefix = "https://raw.githubusercontent.com/"
        if (!raw.startsWith(prefix)) return null
        val parts = raw.removePrefix(prefix).split("/", limit = 3)
        if (parts.size < 3) return null
        return "$JS_DELIVR_HOST/gh/${parts[0]}/${parts[1]}@${parts[2]}"
    }

    private fun String.hostLabel(): String =
        removePrefix("https://").removeSuffix("/")
}
