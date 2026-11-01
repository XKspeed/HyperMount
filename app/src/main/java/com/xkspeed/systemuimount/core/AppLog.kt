package com.xkspeed.systemuimount.core

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志等级
 *
 * 低等级包含高等级：选 [INFO] 时 ERROR/WARN 也照样记
 * [OFF] 只停应用流水 崩溃堆栈仍然会落盘（不然出事没法查）
 */
enum class LogLevel(val label: String, val tag: String) {
    OFF("关闭", "OFF"),
    ERROR("仅错误", "E"),
    WARN("警告", "W"),
    INFO("常规", "I"),
    DEBUG("详细", "D"),
    VERBOSE("全部", "V"),
}

/**
 * 全局应用日志
 *
 * 取代原来只记崩溃的 AppLog：现在是一份统一的流水
 * - 内部目录 `filesDir/logs/app.log`：本次及历次运行的流水（单文件上限 1MB，超了砍掉前半）
 * - 外部目录 `外部存储/Android/data/<包名>/files/logs/app.log`：同一份副本
 *   用手机文件管理器就能直接看，也能 `adb pull` 出来
 * - 崩溃仍然单独落一份 `crash-*.log`（完整堆栈），同时在 app.log 里记一条 E
 *
 * 等级存在 SharedPreferences，界面上用下拉切换；低于当前等级的条目直接丢弃，
 * 所以「详细/全部」只在需要排查时开，平时不会把磁盘写满。
 *
 * 另外保留连续崩溃计数：达到阈值置「安全模式」
 * 由 [com.xkspeed.systemuimount.ui.UiConfigState.safeMode] 消费 ——
 * 关掉液态玻璃与预测性返回 让应用至少能打开（否则纯玻璃崩溃会变成打不开的死循环）
 */
object AppLog {

    private const val TAG = "SuiRescue"
    private const val DIR = "logs"
    private const val PREFS = "crash_log"

    /** 记录「已经向用户展示过」的最近崩溃文件绝对路径 避免每次启动都弹窗 */
    private const val KEY_LAST_SHOWN = "last_shown_crash"

    /** 连续崩溃多少次后进入安全模式 */
    private const val SAFE_MODE_THRESHOLD = 2

    /** 多久以内的崩溃算「连续」（毫秒） */
    private const val CONTIGUOUS_WINDOW_MS = 10 * 60 * 1000L

    /** 单个日志文件上限 超了砍掉前半 防止无限膨胀 */
    private const val MAX_BYTES = 1024 * 1024L

    private val writeLock = Any()

    private lateinit var appContext: Context

    /** install 之前的兜底上下文：万一定时器/异常路径在 install 前就调了这里的函数 不该再崩一次 */
    private var fallbackContext: Context? = null

    /** 是否已安装（避免重复安装把旧 handler 链断掉） */
    private var installed = false

    /** 崩溃时正在执行的动作描述 写进崩溃文件头部 */
    @Volatile
    private var stage: String = "启动"

    /** 当前等级：界面直接读它 改完立即重组 */
    var level: LogLevel by mutableStateOf(LogLevel.INFO)
        private set

    fun install(context: Context) {
        if (installed) return
        installed = true
        appContext = context.applicationContext
        fallbackContext = context.applicationContext

        level = readLevel(context)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(thread, throwable)
            } catch (t: Throwable) {
                Log.e(TAG, "写崩溃日志失败", t)
            }
            // 交给系统处理（弹崩溃框、杀进程） 别吞掉
            previous?.uncaughtException(thread, throwable)
        }

        i(
            "应用启动",
            "pid=${android.os.Process.myPid()} ${Build.MANUFACTURER} ${Build.MODEL} " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) 等级=${level.label}",
        )
    }

    // ------------------------------------------------------------------
    // 等级
    // ------------------------------------------------------------------

    /**
     * 切换等级并落盘
     *
     * 落盘前不判断等级：否则从「关闭」切到任何档位时 这条切换记录会被新等级吞掉，
     * 事后看日志会发现中间断了一段。
     */
    fun setLevel(context: Context, next: LogLevel) {
        if (next == level) return
        val old = level
        level = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("level", next.name)
            .apply()
        forceWrite(next, "日志", "等级 $old.label → ${next.label}")
    }

    private fun readLevel(context: Context): LogLevel {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("level", null)
        return LogLevel.entries.firstOrNull { it.name == raw } ?: LogLevel.INFO
    }

    // ------------------------------------------------------------------
    // 写日志
    // ------------------------------------------------------------------

    /** 标记当前阶段 崩溃时一起记录（例："渲染主页"、"切换到关于页"） */
    fun stage(name: String) {
        stage = name
        d("阶段", name)
    }

    /** 常规流水（原来 AppLog.log 的口径） */
    fun log(tag: String, msg: String) = i(tag, msg)

    fun v(tag: String, msg: String) = write(LogLevel.VERBOSE, tag, msg, null)

    fun d(tag: String, msg: String) = write(LogLevel.DEBUG, tag, msg, null)

    fun i(tag: String, msg: String) = write(LogLevel.INFO, tag, msg, null)

    fun w(tag: String, msg: String, t: Throwable? = null) = write(LogLevel.WARN, tag, msg, t)

    fun e(tag: String, msg: String, t: Throwable? = null) = write(LogLevel.ERROR, tag, msg, t)

    /** 兼容旧调用：warn 等价于 W 级 */
    fun warn(tag: String, msg: String, t: Throwable? = null) = w(tag, msg, t)

    private fun write(lv: LogLevel, tag: String, msg: String, t: Throwable?) {
        if (!installed) return
        if (lv > level) return
        val full = if (t != null) "$msg | ${describe(t)}" else msg
        forceWrite(lv, tag, full)
    }

    /** 不判断等级直接写（崩溃与等级切换用） */
    private fun forceWrite(lv: LogLevel, tag: String, msg: String) {
        try {
            val line = "${ts()} ${lv.tag}/$tag: $msg\n"
            synchronized(writeLock) {
                appendTo(appFile(), line)
                externalDir()?.let { appendTo(it, line) }
            }
        } catch (t: Throwable) {
            // 日志失败不能反过来把 App 搞崩
            Log.e(TAG, "写日志失败", t)
        }
    }

    private fun describe(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { t.printStackTrace(it) }
        return "${t.javaClass.simpleName}: ${t.message ?: ""}\n$sw"
    }

    // ------------------------------------------------------------------
    // 崩溃
    // ------------------------------------------------------------------

    private fun writeCrash(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val header = buildString {
            appendLine("===== 崩溃时间 ${ts()} =====")
            appendLine("线程：${thread.name}")
            appendLine("阶段：$stage")
            appendLine("系统：${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("设备：${Build.DEVICE} ${Build.FINGERPRINT}")
            appendLine()
        }
        val body = header + sw.toString()
        val ctx = ctx() ?: return

        // 1. 单独一份崩溃文件（完整堆栈）
        val f = crashFile()
        f.parentFile?.mkdirs()
        f.writeText(body)
        val cache = File(ctx.cacheDir, DIR)
        cache.mkdirs()
        runCatching { File(cache, f.name).writeText(body) }
        // 2. 外部副本
        runCatching {
            val ext = crashExternalFile()
            ext?.parentFile?.mkdirs()
            ext?.writeText(body)
        }
        // 3. 主流水里也要留痕：不管当前等级多低 崩溃都是 E
        forceWrite(
            LogLevel.ERROR,
            "崩溃",
            "线程 ${thread.name} 阶段 $stage\n$body",
        )

        Log.e(TAG, "崩溃已记录：${f.absolutePath}\n$body")
        noteCrash()
    }

    /** 最近一次崩溃文件（按修改时间最新的那个） */
    fun lastCrashFile(): File? {
        val ctx = ctx() ?: return null
        val dir = File(ctx.filesDir, DIR)
        val files = dir.listFiles { f -> f.name.startsWith("crash-") } ?: return null
        return files.maxByOrNull { it.lastModified() }
    }

    /** 最近一次崩溃的内容 没有则空串 */
    fun lastCrashText(): String =
        lastCrashFile()?.takeIf { it.length() > 0 }?.readText().orEmpty()

    /**
     * 最近一次崩溃文件 但**仅当它还从未向用户展示过**才返回
     * 用于启动弹窗：同一份崩溃只提醒一次 之后启动不再 nag
     */
    fun pendingCrash(): File? {
        val file = lastCrashFile() ?: return null
        if (!file.exists() || file.length() == 0L) return null
        val shown = ctx()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(KEY_LAST_SHOWN, null)
        if (file.absolutePath == shown) return null
        return file
    }

    /** 标记某次崩溃已经向用户展示过（下次启动不再弹） */
    fun markCrashShown(file: File) {
        val ctx = ctx() ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_SHOWN, file.absolutePath)
            .apply()
    }

    /** 外部目录里的崩溃副本（给用户看的那个） */
    fun externalCrashFile(): File? = crashExternalFile()?.takeIf { it.exists() }

    private fun crashExternalFile(): File? {
        val ext = externalDir() ?: return null
        return File(ext.parentFile, "crash-latest.log")
    }

    // ------------------------------------------------------------------
    // 应用日志读取（给界面用）
    // ------------------------------------------------------------------

    /** 应用日志文件（内部目录那份） */
    fun file(): File? = ctx()?.let { File(File(it.filesDir, DIR), "app.log") }

    /** 界面上显示用：优先给用户看得见的外部副本 */
    fun visibleFile(): File? = externalDir()?.takeIf { it.exists() } ?: file()

    fun text(): String = file()?.takeIf { it.exists() }?.readText().orEmpty()

    /** 清空流水（崩溃文件保留 那是现场） */
    fun clear() {
        runCatching {
            synchronized(writeLock) {
                file()?.writeText("")
                externalDir()?.writeText("")
            }
        }
        Log.i(TAG, "应用日志已清空")
    }

    // ------------------------------------------------------------------
    // 安全模式
    // ------------------------------------------------------------------

    /** 当前是否处于安全模式（连续崩溃超阈值） */
    fun safeMode(): Boolean {
        val ctx = ctx() ?: return false
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = p.getInt("count", 0)
        val last = p.getLong("last", 0L)
        return count >= SAFE_MODE_THRESHOLD && System.currentTimeMillis() - last < CONTIGUOUS_WINDOW_MS
    }

    /** 成功启动一次就清零计数（说明能跑起来） */
    fun markAlive() {
        val ctx = ctx() ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("count", 0)
            .putLong("last", System.currentTimeMillis())
            .apply()
    }

    /** 记一次崩溃（返回是否进入安全模式） */
    private fun noteCrash() {
        val ctx = ctx() ?: return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = p.getLong("last", 0L)
        val count = if (now - last < CONTIGUOUS_WINDOW_MS) p.getInt("count", 0) + 1 else 1
        p.edit().putInt("count", count).putLong("last", now).apply()
    }

    // ------------------------------------------------------------------
    // 落盘细节
    // ------------------------------------------------------------------

    /** 拿上下文；install 没跑过就用兜底 再没有就 null（调用方各自降级） */
    private fun ctx(): Context? =
        if (::appContext.isInitialized) appContext else fallbackContext

    private fun appFile(): File {
        val ctx = ctx()
            ?: return File(System.getProperty("java.io.tmpdir"), "sui-app.log")
        return File(File(ctx.filesDir, DIR), "app.log")
    }

    private fun crashFile(): File {
        val ctx = ctx()
            ?: return File(System.getProperty("java.io.tmpdir"), "sui-crash.log")
        val name = "crash-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.log"
        return File(File(ctx.filesDir, DIR), name)
    }

    /** 外部可读目录；拿不到外部存储就返回 null */
    private fun externalDir(): File? {
        val ctx = ctx() ?: return null
        val ext = ctx.getExternalFilesDir(null) ?: return null
        return File(File(ext, DIR), "app.log")
    }

    private fun appendTo(f: File, text: String) {
        f.parentFile?.mkdirs()
        if (f.length() + text.length > MAX_BYTES) {
            // 砍掉前半 留最近的一半 比整个清空好排查
            val keep = runCatching { f.readText() }.getOrDefault("")
            val half = keep.length / 2
            val cut = if (half > 0) keep.substring(half) else ""
            f.writeText("===== 日志超过上限 已截断旧内容 =====\n" + cut)
        }
        f.appendText(text)
    }

    private fun ts(): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
