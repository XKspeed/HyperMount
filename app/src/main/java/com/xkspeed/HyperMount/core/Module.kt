package com.xkspeed.HyperMount.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 日志 + 救砖模式的崩溃上限 比系统默认的 12 宽 给音量键留出反应时间 */
const val CRASH_LIMIT_MANUAL = 30

/** 一次 boot 取回的全部状态（对应 webctl.sh boot 的 ## 分节输出 + settings.conf） */
data class ModuleState(
    // ##VERSION
    val version: String = "",
    // module.prop 的 versionCode：更新检查靠它和清单里的模块版本比大小
    val versionCode: String = "",
    // module.prop 的 author：不是 XKspeed 就不是这套脚本 调不了 sh
    val moduleAuthor: String = "",
    // ##PID
    val pid: String = "",
    // ##STATUS
    val running: String = "",
    val rescueSize: Long = 0L,
    val targetPath: String = "",
    val targetSize: Long = 0L,
    val targetOk: Boolean = false,
    val targetTime: String = "",
    // ##RESCUE
    val crashLimit: String = "",
    val rescueOff: Boolean = false,
    // ##ENV
    val androidVer: String = "",
    val androidSdk: String = "",
    val osName: String = "",
    val model: String = "",
    // ##KEYS
    val keyUp: String = "",
    val keyDown: String = "",
    // ##ENV saved_assess：conf 里真的做过校验（VOLUME_CHECK_ASSESS=1）才为 true
    // 不能拿 keyUp/keyDown 非空当已校验 —— webctl 没校验过也会按能力位扫出设备
    val volumeAssessed: Boolean = false,
    // settings.conf
    val logMode: String = "0",
    val keyTimeout: String = "30",
    val fatalThreshold: String = "3",
    val protectCount: String = "0",
    val logDir: String = "",
) {
    val rescueReady: Boolean get() = rescueSize > 0
    val daemonRunning: Boolean get() = running.isNotBlank()
    val rescueLimitText: String get() = if (crashLimit.isBlank()) "—" else crashLimit

    /**
     * 界面的乐观更新：把待写入的一项先盖到旧状态上。
     *
     * 开关和下拉不能等 root shell 跑完再动，点了就得有反应；
     * 真实写入交给后台，1 秒后 readState 回读，对不上再按实际值把界面拉回来。
     */
    fun withConf(key: String, value: String): ModuleState = when (key) {
        "LOG_MODE" -> copy(logMode = value.ifBlank { "0" })
        "KEY_TIMEOUT" -> copy(keyTimeout = value)
        "FATAL_THRESHOLD" -> copy(fatalThreshold = value)
        "LOG_DIR" -> copy(logDir = value)
        "TARGET_APK_PATH" -> copy(targetPath = value)
        // 下面两个不是 settings.conf 里的键 是对应写操作的占位 用于写前先把界面改掉
        "RESCUE_OFF" -> copy(rescueOff = value == "1")
        "CRASH_LIMIT" -> copy(crashLimit = value)
        else -> this
    }

    /** 回读校验用：取这一项在状态里的当前值 */
    fun confValue(key: String): String = when (key) {
        "LOG_MODE" -> logMode
        "KEY_TIMEOUT" -> keyTimeout
        "FATAL_THRESHOLD" -> fatalThreshold
        "LOG_DIR" -> logDir
        "TARGET_APK_PATH" -> targetPath
        "RESCUE_OFF" -> if (rescueOff) "1" else "0"
        "CRASH_LIMIT" -> crashLimit
        else -> ""
    }
}

/** 目录项（对应 webctl.sh ls 的 名称|类型|大小） */
data class DirEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
)

object Module {

    /** 上一次记过日志的 LOG_DIR：值没变就不再写 防止守护每 2 秒刷新时刷屏 */
    @Volatile
    private var lastLogDirLogged: String? = null

    /** 读取完整状态：一次 boot + 一次 cat 配置 都在 root shell 里  */
    suspend fun readState(): ModuleState = withContext(Dispatchers.IO) {
        val boot = Root.webctl("boot")
        val conf = Root.exec("cat ${Root.q(Root.CONF)} 2>/dev/null")
        val prop = Root.exec("cat ${Root.q(Root.MODULE_PROP)} 2>/dev/null")
        val sec = parseSections(boot)
        val c = parseConf(conf)
        val p = parseConf(prop)

        val st = sec["STATUS"].orEmpty()
        val rs = sec["RESCUE"].orEmpty()
        val env = sec["ENV"].orEmpty()
        val keys = sec["KEYS"].orEmpty()

        // 日志目录不再兜底：模块默认就是空的 空着就按「未设置」处理
        // 以前兜底到 /data/adb 下的隐藏目录 用户既不知道日志在哪 也管不了
        val logDir = c["LOG_DIR"].orEmpty().trim()
        // 界面显示的就是这里读出来的值：只在值变化时记一条 避免守护刷新时刷屏
        if (logDir != lastLogDirLogged) {
            lastLogDirLogged = logDir
            AppLog.i("状态", "LOG_DIR=${logDir.ifBlank { "(未设置)" }}")
        }

        return@withContext ModuleState(
            version = sec["VERSION"]?.get("version").orEmpty(),
            versionCode = p["versionCode"].orEmpty(),
            moduleAuthor = p["author"].orEmpty(),
            pid = sec["PID"]?.get("pid").orEmpty(),
            running = st["running"].orEmpty(),
            rescueSize = st["rescue"]?.toLongOrNull() ?: 0L,
            targetPath = c["TARGET_APK_PATH"].orEmpty(),
            targetSize = st["targetsize"]?.toLongOrNull() ?: 0L,
            targetOk = st["targetok"] == "1",
            targetTime = st["targettime"].orEmpty(),
            crashLimit = rs["limit"].orEmpty(),
            rescueOff = rs["state"] == "off",
            androidVer = env["android_ver"].orEmpty(),
            androidSdk = env["android_sdk"].orEmpty(),
            osName = env["os_name"].orEmpty(),
            model = env["model"].orEmpty(),
            keyUp = keys["up"].orEmpty(),
            keyDown = keys["down"].orEmpty(),
            volumeAssessed = env["saved_assess"] == "1",
            logMode = c["LOG_MODE"].orEmpty().ifBlank { "0" },
            keyTimeout = c["KEY_TIMEOUT"].orEmpty().ifBlank { "30" },
            fatalThreshold = c["FATAL_THRESHOLD"].orEmpty().ifBlank { "3" },
            protectCount = c["PROTECT_COUNT"].orEmpty().ifBlank { "0" },
            logDir = logDir,
        )
    }

    /**
     * 只读模块版本（名 + versionCode）。
     *
     * 检查更新可能在首屏状态拉回来之前就完成，那时 [ModuleState.version] 还是空的；
     * 单独读一次 module.prop 比为了比版本号去跑整个 boot 便宜得多。
     * 没装模块 / 没 root 时返回空串与 0，调用方据此跳过模块判定。
     */
    suspend fun propVersion(): Pair<String, Int> = withContext(Dispatchers.IO) {
        val prop = Root.exec("cat ${Root.q(Root.MODULE_PROP)} 2>/dev/null")
        val p = parseConf(prop)
        val name = p["version"].orEmpty()
        val code = p["versionCode"]?.toIntOrNull() ?: 0
        name to code
    }

    /** 写一个配置项 值里的 sed 元字符先转义 避免路径带 & # 时把文件写坏  */
    suspend fun setConf(key: String, value: String): Boolean = withContext(Dispatchers.IO) {
        if (!key.matches(Regex("^[A-Z_]+$"))) return@withContext false
        val sedEsc = value
            .replace("\\", "\\\\")
            .replace("&", "\\&")
            .replace("#", "\\#")
        val f = Root.q(Root.CONF)
        val cmd = buildString {
            append("if grep -q \"^$key=\" $f 2>/dev/null; then ")
            append("sed -i \"s#^$key=.*#$key=$sedEsc#\" $f; ")
            append("else printf '%s\\n' \"$key=$value\" >> $f; fi")
        }
        Root.exec(cmd)
        // 读回确认 不假设 sed 一定成功
        val back = Root.exec("grep \"^$key=\" $f 2>/dev/null | tail -1").trim()
        val ok = back == "$key=$value"
        // 排查「改了没生效」：文件里实际是什么、命令是否报错 都留痕
        if (ok) {
            AppLog.d("配置", "setConf $key 写入成功 back=$back")
        } else {
            AppLog.w(
                "配置",
                "setConf $key 写入失败 期望=$key=$value 实际=${back.ifBlank { "(空)" }} 文件=$f",
            )
        }
        ok
    }

    /**
     * 跑一次脚本，把命令和输出都抄进应用日志
     *
     * 界面页的日志区只展示模块自己的日志文件；webctl 的 OK/FAIL、pm install 的报错
     * 以前只在界面上一闪而过，事后翻 app.log 什么都看不到。
     */
    private suspend fun logged(label: String, block: suspend () -> String): String {
        val out = runCatching { block() }.getOrElse { t ->
            AppLog.e("脚本", "$label 执行异常", t)
            return "ERROR ${t.message ?: t.javaClass.simpleName}"
        }
        AppLog.i("脚本", "$label\n${out.ifBlank { "(无输出)" }}")
        return out
    }

    /** 把 APK 复制到模块根并统一命名为 MiuiSystemUI.apk */
    suspend fun prepare(src: String): String = logged("prepare ${src.substringAfterLast('/')}") {
        Root.webctl("prepare ${Root.q(src)}")
    }

    /**
     * 启动测试
     *
     * 救砖文件直接传模块根那份（用户已经在主页选过）
     * webctl 内部会用 cmp 判断是否需要重新复制
     */
    suspend fun testRun(target: String): String =
        logged("test-run ${target.substringAfterLast('/')}") {
            Root.webctl("test-run ${Root.q(Root.RESCUE_APK)} ${Root.q(target)}")
        }

    suspend fun stop(): String = logged("stop") { Root.webctl("stop") }

    /**
     * 音量键校验（webroot/check.sh）
     * up / down 会阻塞到用户按键或 10 秒超时 所以必须放在 IO 线程
     */
    suspend fun check(sub: String): String = withContext(Dispatchers.IO) {
        logged("check $sub") {
            Root.exec("sh " + Root.q(Root.MODULE_DIR + "/webroot/check.sh") + " " + sub)
        }
    }

    /**
     * 校验通过后落盘：写上 VOLUME_CHECK_ASSESS=1 和时间/环境快照
     * 顺手把实测到的设备路径存下来 救砖时就不用再猜
     */
    suspend fun saveVolumeCheck(upDev: String = "", downDev: String = ""): String =
        withContext(Dispatchers.IO) {
            logged("check save") {
                Root.exec(
                    "sh " + Root.q(Root.MODULE_DIR + "/webroot/check.sh") +
                        " save " + Root.q(upDev) + " " + Root.q(downDev)
                )
            }
        }

    suspend fun setRescueOff(off: Boolean): String =
        logged(if (off) "rescue off" else "rescue on") {
            Root.webctl(if (off) "rescue off" else "rescue on")
        }

    /**
     * 直接把 AMS 崩溃上限改成指定值
     *
     * 日志 + 救砖模式用 30：系统默认 12 太早
     * SystemUI 反复崩几次就到顶 音量键还没按下去就被系统收走了
     * 增强救砖要屏蔽系统救援（999999）走 [setRescueOff] 这里不重复实现
     */
    suspend fun setCrashLimit(limit: Int): String = withContext(Dispatchers.IO) {
        logged("崩溃上限 $limit") {
            Root.exec(
                "settings put global activity_manager_constants " +
                    "\"process_crash_count_limit=$limit,min_crash_interval=120000," +
                    "process_crash_count_reset_interval=43200000\" 2>/dev/null",
            )
        }
        val back = Root.exec(
            "dumpsys activity settings 2>/dev/null | " +
                "grep -oE 'process_crash_count_limit=[0-9]+' | cut -d= -f2 | head -1",
        ).trim()
        AppLog.i("脚本", "崩溃上限回读 ${back.ifBlank { "unknown" }}")
        if (back == limit.toString()) "OK 崩溃上限已设为 $limit"
        else "FAIL 设置未生效（当前 ${back.ifBlank { "unknown" }}）"
    }

    /** 目录列表（root 权限 能看到 /data 下的东西） */
    suspend fun ls(dir: String): List<DirEntry> {
        val out = Root.webctl("ls ${Root.q(dir)}")
        if (out.startsWith("FAIL")) return emptyList()
        return out.lineSequence().mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 3) return@mapNotNull null
            val name = p[0]
            if (name.isBlank()) return@mapNotNull null
            DirEntry(
                name = name,
                path = if (dir.endsWith("/")) "$dir$name" else "$dir/$name",
                isDir = p[1] == "d",
                size = p[2].toLongOrNull() ?: 0L,
            )
        }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() })).toList()
    }

    /** 最近日志：优先读最新日志文件的尾部 拿不到就退回 webctl output */
    suspend fun tailLog(lines: Int = 400): String = withContext(Dispatchers.IO) {
        // 日志目录可能是空串或带引号：先剥引号 空着就不找了 直接让调用方回退 webctl
        val cmd = "d=\$(grep '^LOG_DIR=' ${Root.q(Root.CONF)} 2>/dev/null | cut -d= -f2- | tr -d '\"'); " +
            "[ -z \"\$d\" ] && exit 0; " +
            "f=\$(ls -t \"\$d\"/*.log 2>/dev/null | head -1); " +
            "[ -n \"\$f\" ] && tail -n $lines \"\$f\""
        val out = Root.exec(cmd)
        if (out.isNotBlank()) out else Root.webctl("output")
    }

    fun fmtSize(bytes: Long): String = when {
        bytes >= 1073741824L -> String.format("%.1f GB", bytes / 1073741824.0)
        bytes >= 1048576L -> String.format("%.1f MB", bytes / 1048576.0)
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 日志时间戳形如"2026-10-25 20:58:31.123456789 +0800" 只留到分钟 */
    fun tidyTime(raw: String): String =
        if (raw.length >= 16) raw.substring(0, 16) else raw

    // ---------- 解析 ----------

    /** "##SECTION" 分节 节内是 key=value */
    private fun parseSections(text: String): Map<String, Map<String, String>> {
        val out = LinkedHashMap<String, MutableMap<String, String>>()
        var cur: String? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.startsWith("##")) {
                cur = line.removePrefix("##").trim()
                out[cur] = LinkedHashMap()
                return@forEach
            }
            val i = line.indexOf('=')
            if (i <= 0 || cur == null) return@forEach
            out.getValue(cur)[line.substring(0, i)] = line.substring(i + 1)
        }
        return out
    }

    /** settings.conf：忽略注释与空行 去掉值两端引号 */
    private fun parseConf(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.trimStart().startsWith("#")) return@forEach
            val i = line.indexOf('=')
            if (i <= 0) return@forEach
            var v = line.substring(i + 1).trim()
            if (v.length >= 2 && ((v.first() == '"' && v.last() == '"') ||
                        (v.first() == '\'' && v.last() == '\''))
            ) {
                v = v.substring(1, v.length - 1)
            }
            out[line.substring(0, i)] = v
        }
        return out
    }
}
