package com.xkspeed.HyperMount.core.mcp

import android.content.Context
import android.util.Base64
import com.xkspeed.HyperMount.core.LogConfig
import com.xkspeed.HyperMount.core.Logcat
import com.xkspeed.HyperMount.core.Module
import com.xkspeed.HyperMount.core.Root
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/**
 * 一次测试在 MCP 侧的状态
 *
 * 只被服务线程读写 不进 Compose 状态 —— 界面不展示它，
 * 用普通 @Volatile 就够，省掉跨线程改 snapshot 的顾虑。
 */
object McpTask {
    @Volatile var id: String = ""
    @Volatile var state: String = "idle"      // idle / running / done / failed
    @Volatile var result: String = ""          // normal / rescue_triggered / crash / rescue_failed / error
    @Volatile var resultText: String = ""
    @Volatile var logFile: String = ""
    @Volatile var message: String = ""
    @Volatile var systemUiAlive: Boolean = false
    @Volatile var startedAt: Long = 0L
    @Volatile var finishedAt: Long = 0L

    fun markRunning(id: String, logFile: String, at: Long) {
        this.id = id
        state = "running"
        result = ""
        resultText = ""
        message = ""
        systemUiAlive = false
        this.logFile = logFile
        startedAt = at
        finishedAt = 0L
    }

    fun markDone(result: String, text: String, alive: Boolean, msg: String) {
        state = "done"
        this.result = result
        resultText = text
        systemUiAlive = alive
        message = msg
        finishedAt = System.currentTimeMillis()
    }

    fun markFailed(msg: String) {
        state = "failed"
        result = "error"
        resultText = "测试未能完成"
        message = msg
        finishedAt = System.currentTimeMillis()
    }
}

/**
 * MCP 工具实现
 *
 * 全部复用现有测试链路：装包 / 抓崩溃 / 判定 / 救砖都在 [Logcat.start] 的脚本里，
 * 这里只负责把 apk 收进来、起一轮、等结束、把结论和日志读出来。
 */
object McpTools {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 待测 apk 与上传文件的存放目录（应用私有） */
    private fun workDir(context: Context): File =
        File(context.filesDir, "mcp").apply { mkdirs() }

    fun uploadDir(context: Context): File =
        File(workDir(context), "uploads").apply { mkdirs() }

    private fun targetFile(context: Context): File = File(workDir(context), "target.apk")

    suspend fun call(
        context: Context,
        name: String,
        args: JsonObject,
        baseUrl: String,
    ): String = when (name) {
        "upload_systemui" -> upload(context, args)
        "run_test" -> runTest(context, args)
        "get_test_result" -> testResult()
        "get_logs" -> logs(context, args, baseUrl)
        else -> fail("未知工具：$name")
    }

    // ---------------- upload_systemui ----------------

    private suspend fun upload(context: Context, args: JsonObject): String = withContext(Dispatchers.IO) {
        val dst = targetFile(context)
        val b64 = args["apk_base64"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        val srcPath = args["apk_path"]?.let { (it as? JsonPrimitive)?.contentOrNull }

        when {
            !b64.isNullOrBlank() -> {
                val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                    ?: return@withContext fail("apk_base64 不是合法的 Base64")
                if (bytes.size < 1024) return@withContext fail("apk 内容过短（${bytes.size} 字节），不是有效安装包")
                dst.writeBytes(bytes)
            }
            !srcPath.isNullOrBlank() -> {
                val src = File(srcPath).canonicalFile
                // 只允许读上传目录里的文件：这个接口不能变成「任意文件读取」
                val root = uploadDir(context).canonicalFile
                if (!src.path.startsWith(root.path + File.separator)) {
                    return@withContext fail("apk_path 必须来自 POST /upload 返回的路径")
                }
                if (!src.isFile) return@withContext fail("文件不存在：$srcPath")
                src.copyTo(dst, overwrite = true)
            }
            else -> return@withContext fail("缺少 apk_base64 或 apk_path")
        }

        val pkg = packageNameOf(context, dst)
        if (pkg.isNullOrBlank()) return@withContext fail("解析不出包名，文件可能不是 apk")

        LogConfig.targetPath = dst.absolutePath
        LogConfig.targetSize = dst.length()
        LogConfig.targetPkg = pkg
        LogConfig.save(context)

        buildJsonObject {
            put("ok", true)
            put("package", pkg)
            put("size", dst.length())
            put("path", dst.absolutePath)
            put("plugin_mode", pkg == Logcat.PLUGIN_PKG)
            put("note", "已设为待测 apk，可以调 run_test")
        }.toString()
    }

    // ---------------- run_test ----------------

    private suspend fun runTest(context: Context, args: JsonObject): String {
        // 占坑要原子：两个请求同时进来 单看 state 会一起放行
        val claimed = synchronized(McpTask) {
            if (McpTask.state == "running" || McpTask.state == "starting") {
                false
            } else {
                McpTask.state = "starting"
                true
            }
        }
        if (!claimed) {
            return fail("已有测试在跑（task ${McpTask.id}），先调 get_test_result 等它结束")
        }
        fun bail(msg: String): String {
            McpTask.state = "idle"
            return fail(msg)
        }

        val target = LogConfig.targetPath
        if (target.isBlank() || !File(target).isFile) {
            return bail("还没有待测 apk，先调 upload_systemui")
        }

        val autoReplace = args["auto_replace"]?.let { (it as? JsonPrimitive)?.booleanOrNull }
            ?: LogConfig.autoReplace
        if (autoReplace) {
            val rescue = LogConfig.rescuePath
            if (rescue.isBlank() || !File(rescue).isFile) {
                return bail("auto_replace 开了但没有救砖 apk：先在 App 里选一次救砖包，或传 auto_replace=false")
            }
        }

        val seconds = (args["seconds"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: LogConfig.seconds)
            .coerceIn(Logcat.MIN_SECONDS, Logcat.MAX_SECONDS)

        val state = runCatching { Module.readState() }.getOrNull()
        val dir = state?.logDir?.takeIf { it.isNotBlank() } ?: "/sdcard/Download/HyperMount"
        val fatalThreshold = state?.fatalThreshold?.toIntOrNull() ?: 3

        val pkg = LogConfig.pkg.ifBlank { Logcat.DEFAULT_PKG }
        val targetPkg = LogConfig.targetPkg
        val pluginMode = targetPkg == Logcat.PLUGIN_PKG

        val staged = when (val r = Logcat.stageTarget(target)) {
            is Logcat.StageResult.Ok -> r.path
            is Logcat.StageResult.Fail -> return bail(r.reason)
        }

        val session = when (
            val r = Logcat.start(
                pkg = pkg,
                seconds = seconds,
                sizeLimitKb = LogConfig.sizeLimitKb,
                targetApk = staged,
                rescueApk = if (autoReplace) LogConfig.rescuePath else "",
                pluginMode = pluginMode,
                fatalThreshold = fatalThreshold,
                dir = dir,
            )
        ) {
            is Logcat.StartResult.Ok -> r.session
            is Logcat.StartResult.Fail -> return bail(r.reason)
        }

        val id = UUID.randomUUID().toString().substring(0, 8)
        McpTask.markRunning(id, session.file, session.startedAt)
        scope.launch {
            runCatching { awaitFinish(context, session, pkg, seconds) }
                .onFailure { McpTask.markFailed(it.message ?: "等待结果时出错") }
        }

        return buildJsonObject {
            put("ok", true)
            put("task_id", id)
            put("state", "running")
            put("seconds", seconds)
            put("pkg", pkg)
            put("auto_replace", autoReplace)
            put("log_file", session.file)
            put("note", "用 get_test_result 查结果，预计 ${seconds + 5} 秒内结束")
        }.toString()
    }

    /**
     * 等脚本收尾 → 读结论 → 补一次进程存活校验
     *
     * 只读 no_crash 不够：卡死、黑屏不会往 crash 缓冲里写东西，
     * 界面其实已经没了。这里再 pidof 一次，进程不在就按异常算。
     */
    private suspend fun awaitFinish(
        context: Context,
        session: Logcat.Session,
        pkg: String,
        seconds: Int,
    ) {
        val deadline = System.currentTimeMillis() + (seconds + 45) * 1000L
        while (System.currentTimeMillis() < deadline) {
            val pid = runCatching { Logcat.runningPid() }.getOrNull()
            if (pid.isNullOrBlank()) break
            delay(500)
        }
        // 脚本退出前还要把结果写进文件 留一点余量
        delay(800)

        val raw = runCatching {
            File(context.cacheDir, "last_result").readText().trim()
        }.getOrDefault("")
        val alive = runCatching {
            Root.exec("pidof $pkg").trim().isNotEmpty()
        }.getOrDefault(false)

        val (key, text) = when {
            raw == "rescued" -> "rescue_triggered" to "触发界面（已自动救砖）"
            raw == "rescue_failed" -> "rescue_failed" to "救砖失败"
            raw == "crash" -> "crash" to "检测到崩溃，未执行救砖"
            raw == "no_crash" && alive -> "normal" to "正常界面"
            raw == "no_crash" && !alive -> "crash" to "未捕获崩溃，但界面进程已不在"
            raw.isBlank() -> "unknown" to "没有拿到结论，脚本可能被中断"
            else -> "unknown" to "结论未知（$raw）"
        }
        McpTask.markDone(key, text, alive, "log=${session.file}")
    }

    // ---------------- get_test_result ----------------

    private fun testResult(): String = buildJsonObject {
        put("ok", true)
        put("task_id", McpTask.id)
        put("state", McpTask.state)
        put("result", McpTask.result)
        put("result_text", McpTask.resultText)
        put("systemui_alive", McpTask.systemUiAlive)
        put("log_file", McpTask.logFile)
        put("message", McpTask.message)
        put("started_at", McpTask.startedAt)
        put("finished_at", McpTask.finishedAt)
    }.toString()

    // ---------------- get_logs ----------------

    private suspend fun logs(context: Context, args: JsonObject, baseUrl: String): String {
        val lines = (args["lines"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 100)
            .coerceIn(1, 2000)
        val file = McpTask.logFile.takeIf { it.isNotBlank() && File(it).isFile }
            ?: latestLogFile()
            ?: return fail("还没有日志：先跑一轮 run_test")

        val p = Logcat.poll(file, lines)
        return buildJsonObject {
            put("ok", true)
            put("file", file)
            put("url", "$baseUrl/log/latest?token=${McpConfig.token}")
            put("running", p.running)
            put("bytes", p.size)
            put("lines", p.text)
        }.toString()
    }

    /** 没有当前任务时退而取日志目录里最新的那份 */
    private suspend fun latestLogFile(): String? {
        val dir = runCatching { Module.readState() }.getOrNull()
            ?.logDir?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            File(dir).listFiles { f -> f.isFile && f.name.startsWith("logcat_") && f.name.endsWith(".log") }
                ?.maxByOrNull { it.lastModified() }
                ?.absolutePath
        }.getOrNull()
    }

    // ---------------- 小工具 ----------------

    private fun packageNameOf(context: Context, file: File): String? = runCatching {
        context.packageManager
            .getPackageArchiveInfo(file.absolutePath, 0)
            ?.packageName
    }.getOrNull()

    private fun fail(msg: String): String = buildJsonObject {
        put("ok", false)
        put("error", msg)
    }.toString()
}
