package com.xkspeed.HyperMount.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * 更新包下载。
 *
 * 先用 Range 探测筛源（只认 206），再拿多个源并行分段拉：
 * 单流慢的时候不是整条换源，而是让每个分段各自重试下一条。
 * 下完按清单里的 sha256 校验 —— 第三方镜像不可信，能验就一定要验。
 */
object Downloader {

    /** 并发分段数上限：先拉满 8 路，单流慢的时候靠它提速 */
    private const val MAX_SEGMENTS = 8

    private const val BUF = 64 * 1024

    /** 进度回调的节流间隔：每 64KB 回一次会把 UI 刷爆 */
    private const val PROGRESS_MS = 200L

    /**
     * 下载 [candidates] 里任意一条能用的地址到 [dest]。
     *
     * @param sha256 清单里给的摘要，空串表示这次不校验（只能退化成比大小）
     * @param onProbe 筛源进度（已探完 / 总数）：这一段最慢 界面要能看见在干嘛
     * @throws IOException 所有源都失败 / 校验不通过
     */
    suspend fun fetch(
        candidates: List<String>,
        dest: File,
        sha256: String = "",
        onProbe: (Int, Int) -> Unit = { _, _ -> },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val list = candidates.distinct()
        val probeTotal = list.size
        val probeDone = java.util.concurrent.atomic.AtomicInteger(0)
        // 先报一次 0：卡片立刻切到「查询镜像中」 不用干等第一条回来
        if (probeTotal > 0) onProbe(0, probeTotal)
        val probes = coroutineScope {
            list.map { url ->
                async(Dispatchers.IO) {
                    val r = url to Mirrors.probe(url, 5_000)
                    onProbe(probeDone.incrementAndGet(), probeTotal)
                    r
                }
            }.awaitAll()
        }
        val usable = probes.filter { it.second.ok }
            // 支持分片的排前面：多源并行优先
            .sortedByDescending { it.second.supportsRange }
        if (usable.isEmpty()) throw IOException("所有下载源都不可用")

        val total = usable.firstOrNull { it.second.size > 0 }?.second?.size ?: 0L
        val ranged = usable.filter { it.second.supportsRange }.map { it.first }

        dest.parentFile?.mkdirs()
        dest.delete()

        if (ranged.isEmpty() || total <= 0) {
            // 没有一条支持 Range：退回单流，能下完就行
            single(usable.map { it.first }, dest, total, onProgress)
        } else {
            segmented(ranged, total, dest, onProgress)
        }

        // 校验：有摘要比摘要 没有就至少比大小
        if (sha256.isNotBlank()) {
            val actual = sha256Of(dest)
            if (!actual.equals(sha256.trim(), ignoreCase = true)) {
                dest.delete()
                throw IOException("校验失败 期望 ${sha256.take(12)}… 实际 ${actual.take(12)}…")
            }
        } else if (total > 0 && dest.length() != total) {
            dest.delete()
            throw IOException("大小不符 期望 ${total} 实际 ${dest.length()}")
        }
        dest
    }

    // ---------------- 单流 ----------------

    private fun single(
        urls: List<String>,
        dest: File,
        expected: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        var last: Exception? = null
        for (url in urls) {
            try {
                val conn = open(url)
                try {
                    if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: expected
                    var done = 0L
                    dest.outputStream().use { out ->
                        conn.inputStream.use { input ->
                            val buf = ByteArray(BUF)
                            while (true) {
                                val r = input.read(buf)
                                if (r < 0) break
                                out.write(buf, 0, r)
                                done += r
                                onProgress(done, total)
                            }
                        }
                    }
                    return
                } finally {
                    runCatching { conn.disconnect() }
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("下载失败")
    }

    // ---------------- 分段 ----------------

    private suspend fun segmented(
        urls: List<String>,
        total: Long,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val count = when {
            // 小文件（模块 zip 几十 KB）切了反而多几轮握手
            total < 256 * 1024 -> 1
            else -> MAX_SEGMENTS
        }
        val chunk = (total + count - 1) / count

        val done = AtomicLong(0)
        val lastEmit = AtomicLong(0)

        fun emit(now: Long) {
            val ms = System.currentTimeMillis()
            val prev = lastEmit.get()
            if (now >= total || ms - prev >= PROGRESS_MS) {
                if (lastEmit.compareAndSet(prev, ms)) onProgress(now, total)
            }
        }

        java.io.RandomAccessFile(dest, "rw").use { raf ->
            raf.setLength(total)
            val channel = raf.channel
            coroutineScope {
                (0 until count).map { i ->
                    async(Dispatchers.IO) {
                        val start = i * chunk
                        val end = minOf(total - 1, start + chunk - 1)
                        if (start > end) return@async
                        var attempt = 0
                        var last: Exception? = null
                        // 每段最多把候选源轮一圈半：源多的时候不至于一条失败就整体放弃
                        while (attempt < urls.size * 2) {
                            val url = urls[(i + attempt) % urls.size]
                            try {
                                fetchRange(url, start, end, channel, done, total, ::emit)
                                return@async
                            } catch (e: Exception) {
                                last = e
                                attempt++
                            }
                        }
                        throw last ?: IOException("分段 ${i} 失败")
                    }
                }.awaitAll()
            }
        }
    }

    private fun fetchRange(
        url: String,
        start: Long,
        end: Long,
        channel: FileChannel,
        done: AtomicLong,
        total: Long,
        emit: (Long) -> Unit,
    ) {
        val conn = open(url)
        try {
            conn.setRequestProperty("Range", "bytes=$start-$end")
            if (conn.responseCode != 206) throw IOException("不支持分段 HTTP ${conn.responseCode}")
            conn.inputStream.use { input ->
                val buf = ByteArray(BUF)
                var pos = start
                while (pos <= end) {
                    val want = minOf(buf.size.toLong(), end - pos + 1).toInt()
                    val r = input.read(buf, 0, want)
                    if (r < 0) break
                    // FileChannel 的定位写是线程安全的：每个分段写自己的区间，不会互相踩
                    channel.write(ByteBuffer.wrap(buf, 0, r), pos)
                    pos += r
                    emit(done.addAndGet(r.toLong()))
                }
                if (pos != end + 1) throw IOException("分段不完整 ${pos - start}/${end - start + 1}")
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "HyperMount")
            setRequestProperty("Accept", "*/*")
            // 别让镜像/CDN 回一份旧文件
            setRequestProperty("Cache-Control", "no-cache")
        }

    /** 文件摘要：下完拿它跟清单里的 sha256 比 */
    fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUF)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
