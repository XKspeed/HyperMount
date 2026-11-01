package com.xkspeed.HyperMount.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * 仓库根目录的 update.json
 *
 * 字段都给了默认值 少写几个也能解析 不至于因为仓库里改格式就崩
 */
@Serializable
data class UpdateInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val url: String = "",
    val changelog: String = "",
)

/**
 * 更新检查
 *
 * 只读仓库里的 update.json 不碰 release API：一份静态文件就够。
 * 直连 GitHub 常常连不上（尤其移动网络），所以按「直连 → 加速镜像 → CDN」的顺序逐条试，
 * 哪条先回就用哪条，并把这条的代理前缀记住：下载链接也套同一个前缀，
 * 免得「能查到更新、点下载却打不开」。
 *
 * 启动时后台查一次 超时就当没更新 不能让网络拖住启动
 */
object Update {

    private const val REPO = "XKspeed/SystemUIRescue"
    private const val BRANCH = "main"

    /** 原始清单（走 GitHub raw） */
    private const val RAW_MANIFEST =
        "https://raw.githubusercontent.com/$REPO/$BRANCH/update.json"

    /**
     * 代理前缀：空串是直连，其余是国内常用的 GitHub 加速站
     * 这些站同时转发 raw 与 releases/download，下载链接能直接套
     */
    private val PROXIES = listOf(
        "",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://ghfast.top/",
        "https://github.moeyy.xyz/",
    )

    /** 不走代理也能拿到的镜像/CDN：直连被墙时这几条往往还活着（只用于清单，不能转发下载） */
    private val MIRRORS = listOf(
        "https://cdn.jsdelivr.net/gh/$REPO@$BRANCH/update.json",
        "https://raw.gitmirror.com/$REPO/$BRANCH/update.json",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 本次启动扫通的代理前缀（空串=直连，null=还没查过） */
    @Volatile
    private var liveProxy: String? = null

    // 本地版本走 PackageManager：用 MT 改过 AndroidManifest 的包装上来 这里读到的就是改后的值
    val currentCode: Int get() = AppVersion.code.toInt()
    val currentName: String get() = AppVersion.name

    /** 超时返回 null 与「没有更新」区分开：调用方可以区分"没查到"和"已最新" */
    suspend fun check(timeoutMs: Long = 15_000): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching { fetch(timeoutMs) }.getOrNull()
    }

    fun isNewer(info: UpdateInfo?): Boolean = info != null && info.versionCode > currentCode

    /** 变更按行拆开 空行丢掉 列表里好排版 */
    fun changelogLines(info: UpdateInfo): List<String> =
        info.changelog.lines()
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.isNotEmpty() }

    /** 显示用的版本号：清单里没写 name 就退回 code */
    fun label(info: UpdateInfo): String =
        info.versionName.ifBlank { info.versionCode.toString() }

    /** 仓库地址的 releases 页（清单里没给下载地址时的兜底） */
    fun releasesPage(): String = downloadUrl("")

    /**
     * 给下载地址套上本次扫通的代理。
     * GitHub 直连能通就不加前缀；通了镜像才查到的更新 这里同样走镜像。
     */
    fun downloadUrl(raw: String): String {
        val url = raw.trim().ifEmpty { "https://github.com/$REPO/releases" }
        val proxy = liveProxy.orEmpty()
        if (proxy.isEmpty()) return url
        // 已经是代理/镜像地址就不重复加前缀
        if (PROXIES.any { it.isNotEmpty() && url.startsWith(it) }) return url
        return proxy + url
    }

    private suspend fun fetch(timeoutMs: Long): UpdateInfo? = withContext(Dispatchers.IO) {
        val candidates = buildList {
            PROXIES.forEach { proxy -> add(proxy to proxy + RAW_MANIFEST) }
            MIRRORS.forEach { add("" to it) }
        }.let { list ->
            // 本次已经扫通过一条 就把它排到最前：手动重查时不用再从死链开始试
            val known = liveProxy ?: return@let list
            list.sortedByDescending { if (it.first == known) 1 else 0 }
        }
        // 阻塞式 HttpURLConnection 不响应协程取消，超时只能自己在每条之前掐：
        // 预算用完就停，免得一串死链把启动后的后台任务拖到半分钟
        val deadline = System.currentTimeMillis() + timeoutMs
        for ((proxy, url) in candidates) {
            if (System.currentTimeMillis() >= deadline) break
            val info = fetchOne(url) ?: continue
            liveProxy = proxy
            AppLog.log(
                "更新",
                "命中镜像 ${proxy.ifEmpty { "直连/CDN" }} 远端 ${info.versionName}(${info.versionCode}) " +
                    "本地 ${currentName}($currentCode)",
            )
            return@withContext info
        }
        AppLog.log("更新", "所有镜像都没拿到 update.json")
        null
    }

    /** 单条地址拉一次：解析失败（被劫持成 HTML 之类）也算失败 继续试下一条 */
    private fun fetchOne(url: String): UpdateInfo? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            // 每条只给 3 秒：挂了就赶紧换下一条，别让一条死链吃掉整个预算
            connectTimeout = 3_000
            readTimeout = 3_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            // 走 CDN 不能吃到旧清单 否则刚发的版本永远查不到
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            // 代理挂了常返回一段 HTML，decode 会抛 正好当失败处理
            val info = json.decodeFromString<UpdateInfo>(text)
            if (info.versionCode <= 0 && info.versionName.isBlank()) return@runCatching null
            info
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrNull()
}
