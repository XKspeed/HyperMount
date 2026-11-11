package com.xkspeed.HyperMount.core.mcp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.xkspeed.HyperMount.core.ConfigBus
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
    @Volatile var state: String = "idle"      // idle / starting / running / done / failed
    @Volatile var result: String = ""          // normal / rescue_triggered / crash / rescue_failed / error
    @Volatile var resultText: String = ""
    @Volatile var logFile: String = ""
    @Volatile var message: String = ""
    @Volatile var systemUiAlive: Boolean = false
    /** 这一轮测的是哪条链：systemui（界面替换）/ apk（普通应用） */
    @Volatile var mode: String = ""
    /** 实际监视的进程包名 */
    @Volatile var monitorPkg: String = ""
    /** systemui 链路：开跑那一刻的救砖计数，用来判断这一轮有没有真的救过 */
    @Volatile var baseProtectCount: Int = -1
    @Volatile var startedAt: Long = 0L
    @Volatile var finishedAt: Long = 0L

    fun markRunning(
        id: String,
        logFile: String,
        at: Long,
        mode: String,
        monitorPkg: String,
        baseProtectCount: Int = -1,
    ) {
        this.id = id
        state = "running"
        result = ""
        resultText = ""
        message = ""
        systemUiAlive = false
        this.logFile = logFile
        this.mode = mode
        this.monitorPkg = monitorPkg
        this.baseProtectCount = baseProtectCount
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

    /** 占坑：已有任务在跑就别再起一轮。原子判断，两个请求同时进来不会一起放行 */
    fun claim(): Boolean = synchronized(this) {
        if (state == "running" || state == "starting") false else {
            state = "starting"
            true
        }
    }

    fun release() {
        synchronized(this) { if (state == "starting") state = "idle" }
    }
}

/**
 * MCP 工具实现
 *
 * 两条测试链路各有一个「上传即开跑」的入口，互不干扰：
 * - [updateSystemUiAndRun]：界面页那条 —— 把包覆盖成模块挂载点的 MiuiSystemUI.apk 再重启，
 *   崩了由模块守护 + 音量键救砖
 * - [updateApkAndRun]：主页那条 —— pm install 后抓 logcat，崩了按需装回救砖包
 *
 * 两个入口只写自己那条链的槽位与文件，避免「界面页指向普通 apk」这种会把手机测黑的状态。
 */
object McpTools {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 待测 apk 与上传文件的存放目录（应用私有） */
    private fun workDir(context: Context): File =
        File(context.filesDir, "mcp").apply { mkdirs() }

    fun uploadDir(context: Context): File =
        File(workDir(context), "uploads").apply { mkdirs() }

    /** 主页那条链的待测包 */
    private fun apkFile(context: Context): File = File(workDir(context), "target_apk.apk")

    /** 界面页那条链的待测包：单独存一份，两个槽位不能指到同一个文件 */
    private fun systemUiFile(context: Context): File = File(workDir(context), "target_systemui.apk")

    suspend fun call(
        context: Context,
        name: String,
        args: JsonObject,
        baseUrl: String,
    ): String = when (name) {
        "update_systemui_and_run_systemui_test" -> updateSystemUiAndRun(context, args)
        "update_apk_run_apk_test" -> updateApkAndRun(context, args)
        "get_result" -> testResult()
        "get_log" -> logs(context, args, baseUrl)
        else -> fail("未知工具：$name")
    }

    // ---------------- 收 apk ----------------

    private sealed interface Received {
        class Ok(val pkg: String) : Received
        class Err(val json: String) : Received
    }

    /**
     * 把 apk 落到指定文件并解析包名
     *
     * apk_path 只允许读上传目录里的文件：这个接口不能变成「任意文件读取」。
     */
    private suspend fun receiveApk(
        context: Context,
        args: JsonObject,
        dst: File,
    ): Received = withContext(Dispatchers.IO) {
        val b64 = args["apk_base64"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        val srcPath = args["apk_path"]?.let { (it as? JsonPrimitive)?.contentOrNull }

        when {
            !b64.isNullOrBlank() -> {
                val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                    ?: return@withContext Received.Err(fail("apk_base64 不是合法的 Base64"))
                if (bytes.size < 1024) {
                    return@withContext Received.Err(fail("apk 内容过短（${bytes.size} 字节），不是有效安装包"))
                }
                dst.writeBytes(bytes)
            }
            !srcPath.isNullOrBlank() -> {
                val src = File(srcPath).canonicalFile
                val root = uploadDir(context).canonicalFile
                if (!src.path.startsWith(root.path + File.separator)) {
                    return@withContext Received.Err(fail("apk_path 必须来自 POST /upload 返回的路径"))
                }
                if (!src.isFile) return@withContext Received.Err(fail("文件不存在：$srcPath"))
                src.copyTo(dst, overwrite = true)
            }
            else -> return@withContext Received.Err(fail("缺少 apk_base64 或 apk_path"))
        }

        val pkg = packageNameOf(context, dst)
        if (pkg.isNullOrBlank()) return@withContext Received.Err(fail("解析不出包名，文件可能不是 apk"))
        Received.Ok(pkg)
    }

    // ---------------- update_systemui_and_run_systemui_test ----------------

    /**
     * 界面页链路：换掉挂载点的 MiuiSystemUI.apk 并重启 SystemUI
     *
     * 这条链路是「真替换」，新包有问题会直接黑屏，只能靠音量键救砖拉回；
     * 所以强制要求 confirm=true，且在起跑前把缺的东西一次性说清。
     */
    private suspend fun updateSystemUiAndRun(context: Context, args: JsonObject): String {
        val confirm = args["confirm"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false
        if (!confirm) {
            return fail(
                "这条工具会把 SystemUI 换成待测包并重启：新包有问题会黑屏，只能靠音量键救砖拉回。" +
                    "确认要测请传 confirm=true",
            )
        }

        val st = runCatching { Module.readState() }.getOrNull()
            ?: return fail("读不到模块状态：模块没装或 root 没授权")
        if (st.moduleAuthor.isBlank()) {
            return fail("没有检测到 HyperMount 模块，界面替换链路用不了")
        }
        if (!st.rescueReady) {
            return fail("还没选救砖界面：先在 App 的「界面」页选一个能正常开机的 SystemUI")
        }
        if (st.logMode == "0") {
            return fail("运行模式是完全禁用，action.sh 会直接退出：先把「设置 → 界面设置」改成「日志 + 救砖」")
        }
        if (st.daemonRunning) {
            return fail("已有测试在跑（守护 PID ${st.pid}），先调 get_result 等它结束或手动停止")
        }
        // 上一轮可能早就收尾了，只是没人来查结果：先结算掉，否则这一轮会被自己拦住
        if (McpTask.state == "running" && McpTask.mode == "systemui") {
            systemUiResult()
        }
        if (!McpTask.claim()) {
            return fail("已有测试在跑（task ${McpTask.id}），先调 get_result 等它结束")
        }

        val dst = systemUiFile(context)
        val pkg = when (val r = receiveApk(context, args, dst)) {
            is Received.Err -> {
                McpTask.release()
                return r.json
            }
            is Received.Ok -> r.pkg
        }
        if (pkg != Logcat.DEFAULT_PKG) {
            McpTask.release()
            return fail("这个工具只接受 ${Logcat.DEFAULT_PKG}，收到的是 $pkg：普通应用请用 update_apk_run_apk_test")
        }

        // 界面页槽位指向这次上传的副本 界面上的「待测界面」随之刷新
        val slotOk = runCatching { Module.setConf("TARGET_APK_PATH", dst.absolutePath) }.getOrDefault(false)
        if (!slotOk) {
            McpTask.release()
            return fail("写不进模块配置（TARGET_APK_PATH），检查 root 授权")
        }
        Handler(Looper.getMainLooper()).post { ConfigBus.bump() }

        val out = runCatching { Module.testRun(dst.absolutePath) }.getOrNull()
        if (out == null || !out.contains("OK")) {
            McpTask.release()
            return fail("启动失败：${firstLine(out ?: "模块没有响应")}")
        }

        val id = UUID.randomUUID().toString().substring(0, 8)
        McpTask.markRunning(
            id = id,
            logFile = "",
            at = System.currentTimeMillis(),
            mode = "systemui",
            monitorPkg = Logcat.DEFAULT_PKG,
            baseProtectCount = st.protectCount.toIntOrNull() ?: 0,
        )

        return buildJsonObject {
            put("ok", true)
            put("task_id", id)
            put("state", "running")
            put("mode", "systemui")
            put("package", pkg)
            put("monitor_package", Logcat.DEFAULT_PKG)
            put("target_slot_updated", true)
            put("size", dst.length())
            put("path", dst.absolutePath)
            put("note", "已替换挂载点并重启 SystemUI，守护已接管。用 get_result 查结果；黑屏时按音量键救砖")
        }.toString()
    }

    // ---------------- update_apk_run_apk_test ----------------

    /**
     * 主页链路：装包 → 抓 N 秒 logcat → 判定崩溃 → 按需救砖
     *
     * SystemUI 本体不走这里：它装不进去，也测不出挂载替换的问题。
     */
    private suspend fun updateApkAndRun(context: Context, args: JsonObject): String {
        val dst = apkFile(context)
        val pkg = when (val r = receiveApk(context, args, dst)) {
            is Received.Err -> return r.json
            is Received.Ok -> r.pkg
        }
        if (pkg == Logcat.DEFAULT_PKG) {
            return fail(
                "$pkg 是 SystemUI 本体，pm install 这条路测不了：请改用 update_systemui_and_run_systemui_test",
            )
        }

        // 插件包跑在 systemui 宿主里，崩溃落在宿主进程名下：
        // 抓日志的包名锁死 com.android.systemui，不跟插件包名走；
        // 其余包名就按 apk 自己的包名抓。
        val logPkg = if (pkg == Logcat.PLUGIN_PKG) Logcat.DEFAULT_PKG else pkg

        LogConfig.targetPath = dst.absolutePath
        LogConfig.targetSize = dst.length()
        LogConfig.targetPkg = pkg
        LogConfig.pkg = logPkg
        LogConfig.save(context)

        val autoReplace = args["auto_replace"]?.let { (it as? JsonPrimitive)?.booleanOrNull }
            ?: LogConfig.autoReplace
        if (autoReplace) {
            val rescue = LogConfig.rescuePath
            if (rescue.isBlank() || !File(rescue).isFile) {
                return fail("auto_replace 开了但没有救砖 apk：先在 App 里选一次救砖包，或传 auto_replace=false")
            }
        }

        if (!McpTask.claim()) {
            return fail("已有测试在跑（task ${McpTask.id}），先调 get_result 等它结束")
        }
        return startApkSession(context, args, pkg, logPkg, autoReplace)
    }

    private suspend fun startApkSession(
        context: Context,
        args: JsonObject,
        targetPkg: String,
        monitorPkg: String,
        autoReplace: Boolean,
    ): String {
        val target = LogConfig.targetPath
        if (target.isBlank() || !File(target).isFile) {
            McpTask.release()
            return fail("还没有待测 apk，先调 update_apk_run_apk_test")
        }

        val seconds = (args["seconds"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: LogConfig.seconds)
            .coerceIn(Logcat.MIN_SECONDS, Logcat.MAX_SECONDS)

        val state = runCatching { Module.readState() }.getOrNull()
        val dir = state?.logDir?.takeIf { it.isNotBlank() } ?: "/sdcard/Download/HyperMount"
        val fatalThreshold = state?.fatalThreshold?.toIntOrNull() ?: 3

        val pluginMode = targetPkg == Logcat.PLUGIN_PKG

        val staged = when (val r = Logcat.stageTarget(target)) {
            is Logcat.StageResult.Ok -> r.path
            is Logcat.StageResult.Fail -> {
                McpTask.release()
                return fail(r.reason)
            }
        }

        val session = when (
            val r = Logcat.start(
                pkg = monitorPkg,
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
            is Logcat.StartResult.Fail -> {
                McpTask.release()
                return fail(r.reason)
            }
        }

        val id = UUID.randomUUID().toString().substring(0, 8)
        McpTask.markRunning(id, session.file, session.startedAt, "apk", monitorPkg, -1)
        scope.launch {
            runCatching { awaitFinish(context, session, monitorPkg, seconds) }
                .onFailure { McpTask.markFailed(it.message ?: "等待结果时出错") }
        }

        return buildJsonObject {
            put("ok", true)
            put("task_id", id)
            put("state", "running")
            put("mode", "apk")
            put("package", targetPkg)
            put("monitor_package", monitorPkg)
            put("plugin_mode", pluginMode)
            put("seconds", seconds)
            put("auto_replace", autoReplace)
            put("size", File(target).length())
            put("path", target)
            put("log_file", session.file)
            put("note", "用 get_result 查结果，预计 ${seconds + 5} 秒内结束")
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

    // ---------------- get_result ----------------

    private suspend fun testResult(): String =
        if (McpTask.mode == "systemui" && McpTask.state != "idle") systemUiResult() else apkResult()

    private fun apkResult(): String = buildJsonObject {
        put("ok", true)
        put("task_id", McpTask.id)
        put("state", McpTask.state)
        put("mode", McpTask.mode)
        put("monitor_package", McpTask.monitorPkg)
        put("result", McpTask.result)
        put("result_text", McpTask.resultText)
        put("systemui_alive", McpTask.systemUiAlive)
        put("log_file", McpTask.logFile)
        put("message", McpTask.message)
        put("started_at", McpTask.startedAt)
        put("finished_at", McpTask.finishedAt)
    }.toString()

    /**
     * 界面页链路的结果
     *
     * 没有 last_result 文件可用，只能看模块守护与救砖计数：
     * 守护还在 = 测试中；守护收了 + 救砖计数涨了 = 崩了并被救回；
     * 守护收了 + 进程还在 = 正常。
     */
    private suspend fun systemUiResult(): String = withContext(Dispatchers.IO) {
        val ds = runCatching { Module.daemonStatus() }.getOrNull()
        val alive = runCatching {
            Root.exec("pidof ${Logcat.DEFAULT_PKG}").trim().isNotEmpty()
        }.getOrDefault(false)
        val protect = ds?.protectCount ?: 0
        val base = McpTask.baseProtectCount
        val delta = if (base >= 0) protect - base else 0

        // 刚起跑那几秒守护还没就位，别当成「已经结束」
        val grace = System.currentTimeMillis() - McpTask.startedAt < 15_000L
        val testing = ds?.testing == true || (McpTask.state == "running" && grace)

        if (testing) {
            McpTask.state = "running"
            McpTask.systemUiAlive = alive
        } else if (McpTask.state == "running") {
            val (key, text) = when {
                delta > 0 -> "rescue_triggered" to "触发救砖，已换回救砖界面"
                alive -> "normal" to "界面正常，守护已收尾"
                else -> "crash" to "未触发救砖，但 SystemUI 进程已不在"
            }
            McpTask.markDone(key, text, alive, if (delta > 0) "本轮救砖 $delta 次" else "守护已收尾")
        }

        buildJsonObject {
            put("ok", true)
            put("task_id", McpTask.id)
            put("state", McpTask.state)
            put("mode", "systemui")
            put("monitor_package", Logcat.DEFAULT_PKG)
            put("result", McpTask.result)
            put("result_text", McpTask.resultText)
            put("systemui_alive", alive)
            put("protect_count", protect)
            put("protect_delta", delta)
            put("message", McpTask.message)
            put("started_at", McpTask.startedAt)
            put("finished_at", McpTask.finishedAt)
        }.toString()
    }

    // ---------------- get_log ----------------

    private suspend fun logs(context: Context, args: JsonObject, baseUrl: String): String {
        val lines = (args["lines"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 100)
            .coerceIn(1, 2000)

        if (McpTask.mode == "systemui") return systemUiLogs(lines, baseUrl)

        val file = McpTask.logFile.takeIf { it.isNotBlank() && File(it).isFile }
            ?: latestLogFile()
            ?: return fail("还没有日志：先跑一轮 update_apk_run_apk_test")

        val p = Logcat.poll(file, lines)
        return buildJsonObject {
            put("ok", true)
            put("mode", "apk")
            put("source", "logcat")
            put("file", file)
            put("url", "$baseUrl/log/latest?token=${McpConfig.token}")
            put("running", p.running)
            put("bytes", p.size)
            put("lines", p.text)
        }.toString()
    }

    /**
     * 界面页链路的日志
     *
     * 模块在检测到 SystemUI 崩溃时抓的是 crash buffer，内容就是 Java 异常栈——
     * 这正是判断「界面坏在哪」要看的东西，不要换成 webctl 的运行输出。
     */
    private suspend fun systemUiLogs(lines: Int, baseUrl: String): String = withContext(Dispatchers.IO) {
        val file = latestModuleLog(McpTask.startedAt)
            ?: return@withContext buildJsonObject {
                put("ok", true)
                put("mode", "systemui")
                put("source", "logcat")
                put("file", "")
                put("lines", "")
                put(
                    "note",
                    "这一轮没有崩溃日志：模块只在检测到 SystemUI 崩溃时才抓 crash buffer。" +
                        "没有日志通常说明没崩；黑屏但没崩请用 get_result 看进程是否还在",
                )
            }.toString()

        val p = Logcat.poll(file, lines)
        buildJsonObject {
            put("ok", true)
            put("mode", "systemui")
            put("source", "logcat")
            put("file", file)
            put("url", "$baseUrl/log/latest?token=${McpConfig.token}")
            put("running", p.running)
            put("bytes", p.size)
            put("lines", p.text)
        }.toString()
    }

    /**
     * 模块侧写的日志（action.sh 用 `${LOG_DIR}/<时间>.log` 命名）
     *
     * [since] 只取这一轮开始之后写过的：日志目录里通常还留着历史文件，
     * 不看时间会把上一轮的崩溃栈当成这一轮的。
     */
    private suspend fun latestModuleLog(since: Long): String? {
        val dir = runCatching { Module.readState() }.getOrNull()
            ?.logDir?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            File(dir).listFiles { f ->
                f.isFile && f.name.endsWith(".log") && !f.name.startsWith("logcat_") &&
                    (since <= 0L || f.lastModified() >= since)
            }?.maxByOrNull { it.lastModified() }?.absolutePath
        }.getOrNull()
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

    private fun firstLine(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(160) ?: ""

    private fun fail(msg: String): String = buildJsonObject {
        put("ok", false)
        put("error", msg)
    }.toString()
}
