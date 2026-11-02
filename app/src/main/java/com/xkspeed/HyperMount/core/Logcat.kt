package com.xkspeed.HyperMount.core

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主页的测试流程
 *
 * 逻辑照搬「双兼容版 logcat.sh」：
 * 1. 后台先 pm install 待测 apk（装 SystemUI 要几秒 不能卡住主流程）
 * 2. 前台等用户设定的等待时长
 * 3. 抓 crash 缓冲区 同时盯着文件大小 超过阈值就执行救砖
 * 4. 救砖直接用私有目录里的救砖包 pm install -r -d -t 最后重启 SystemUI
 *
 * 和模块守护完全解耦：脚本、pid、运行日志、抓到的日志、两份 apk 的临时副本
 * 全部落在 App 私有目录 files/test/ 下，不碰系统临时目录，卸载即清干净。
 * 复制前先按内容比对上一轮留下的副本，一致就复用，省掉一次上百兆的读写。
 */
object Logcat {

    private var app: Context? = null

    /** Application.onCreate 里调一次 之后所有路径都基于私有目录 */
    fun install(context: Context) {
        app = context.applicationContext
    }

    /** 测试工作目录 files/test/ 脚本 / pid / 日志 / apk 副本都在这儿 */
    fun workDir(): String {
        val ctx = app ?: return ""
        val dir = java.io.File(ctx.filesDir, "test").apply { mkdirs() }
        return dir.absolutePath
    }

    private fun path(name: String): String {
        val dir = workDir()
        return if (dir.isBlank()) "" else "$dir/$name"
    }

    private fun scriptFile(): String = path("sui_test.sh")
    private fun pidFile(): String = path("sui_test.pid")
    fun runLog(): String = path("sui_test.log")
    private fun tmpTarget(): String = path("sui_target.apk")
    private fun tmpRescue(): String = path("sui_rescue.apk")

    const val DEFAULT_PKG = "com.android.systemui"
    const val DEFAULT_SECONDS = 30
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 60

    /** 开始测试后等多久再开抓 0 到 20 秒 */
    const val DEFAULT_WAIT = 5
    const val MIN_WAIT = 0
    const val MAX_WAIT = 20

    /** crash 日志超过这个大小就认为崩了 脚本里写死 和原脚本一致 */
    const val SIZE_THRESHOLD = 5120

    /** 发现崩溃后等几秒再救 给系统把日志写完 */
    const val DELAY_AFTER_CRASH = 2

    /** @ 代表 $ 写脚本时不用一路转义 落盘前统一换回来 */
    private val SCRIPT_BODY: String = """
        #!/system/bin/sh
        # 抓 crash 日志 + 崩溃自动救砖
        # $1 日志输出 $2 运行日志 $3 pid 文件 $4 目标包名 $5 等待秒 $6 抓取秒
        # $7 待测 apk（私有目录副本） $8 救砖 apk（私有目录副本）
        # $9 待测 apk 真实路径 $10 救砖 apk 真实路径 —— 只用于日志展示
        OUT="@1"
        RUN="@2"
        PIDF="@3"
        PKG="@4"
        WAIT="@5"
        DUR="@6"
        TARGET_APK="@7"
        RESCUE_APK="@8"
        TARGET_SHOW="@9"
        RESCUE_SHOW="@10"
        [ -z "@TARGET_SHOW" ] && TARGET_SHOW="@TARGET_APK"
        [ -z "@RESCUE_SHOW" ] && RESCUE_SHOW="@RESCUE_APK"
        THRESHOLD=5120
        DELAY=2

        echo @@ > "@PIDF"
        : > "@OUT"
        : > "@RUN"
        exec >>"@RUN" 2>&1

        log() { echo "[@(date +%H:%M:%S)] @1"; }

        rescue() {
            log "开始执行救砖..."
            if [ ! -f "@RESCUE_APK" ]; then
                log "救砖apk不存在 跳过救砖"
                return 1
            fi
            # pm install 由 root shell 自己读文件 路径在私有目录也能装
            # 不再中转一份临时副本 少一次上百兆的复制
            R=$(pm install -r -d -t "@RESCUE_APK" 2>&1)
            RC=@?
            log "pm install 结果 (exit=@RC): @R"
            if [ "@RC" -eq 0 ]; then
                log "救砖成功 重启 SystemUI"
                (sleep 3; killall com.android.systemui 2>/dev/null) &
            else
                log "救砖失败 详见上方输出"
            fi
        }

        watch_crash() {
            log "启动崩溃监控 阈值>@THRESHOLD 字节 触发延迟 @DELAY 秒"
            triggered=0
            END=@(( @(date +%s) + DUR ))
            while [ @(date +%s) -lt @END ]; do
                if [ @triggered -eq 0 ] && [ -f "@LOG_FILE" ]; then
                    SIZE=@(stat -c%s "@LOG_FILE" 2>/dev/null || echo 0)
                    if [ @SIZE -gt @THRESHOLD ]; then
                        log "检测到崩溃日志超过阈值 大小: @SIZE bytes"
                        triggered=1
                        sleep @DELAY
                        rescue
                        log "救砖已执行 停止日志抓取"
                        kill "@LPID" 2>/dev/null
                        return 0
                    fi
                fi
                sleep 1
            done
            log "监控时间到期 退出日志监控"
        }

        mkdir -p "@(dirname "@OUT")"
        LOG_FILE="@OUT"
        log "目标应用: @PKG"
        log "待测apk: @TARGET_SHOW"
        if [ -n "@RESCUE_APK" ]; then
            log "救砖apk: @RESCUE_SHOW"
        else
            log "未开启自动替换 只抓日志"
        fi

        INSTALL_PID=""
        if [ -n "@TARGET_APK" ] && [ -f "@TARGET_APK" ]; then
            log "后台安装待测apk..."
            (R=@(pm install -r -d -t "@TARGET_APK" 2>&1); RC=@?; log "待测apk安装结束 (exit=@RC): @R") &
            INSTALL_PID=@!
        else
            log "没有待测apk 跳过安装"
        fi

        log "等待 @WAIT 秒后开始抓取..."
        sleep "@WAIT"

        OLD=@(pidof "@PKG" 2>/dev/null)
        if [ -n "@OLD" ]; then
            log "当前PID: @OLD"
        else
            log "当前PID: 无（未运行）"
        fi

        logcat -c -b crash 2>/dev/null
        logcat -b crash -v threadtime -f "@OUT" &
        LPID=@!
        log "logcat PID: @LPID"

        trap 'kill @LPID 2>/dev/null; [ -n "@MON" ] && kill @MON 2>/dev/null; rm -f "@PIDF"; exit 0' TERM INT

        MON=""
        if [ -n "@RESCUE_APK" ] && [ -f "@RESCUE_APK" ]; then
            watch_crash &
            MON=@!
            log "监控进程PID: @MON"
        else
            log "没有救砖apk 跳过崩溃监控"
        fi

        log "抓取 @DUR 秒..."
        sleep "@DUR"

        kill "@LPID" 2>/dev/null
        if [ -n "@MON" ]; then kill "@MON" 2>/dev/null; fi
        sleep 1
        kill -9 "@LPID" 2>/dev/null
        if [ -n "@MON" ]; then kill -9 "@MON" 2>/dev/null; fi

        NEW=@(pidof "@PKG" 2>/dev/null)
        if [ -n "@NEW" ]; then
            log "结束PID: @NEW"
        else
            log "结束PID: 无（未运行）"
        fi

        SIZE=@(stat -c%s "@OUT" 2>/dev/null || echo 0)
        log "抓取完成 日志: @OUT"
        log "大小: @((SIZE / 1024))KB"
        if [ "@SIZE" -eq 0 ]; then
            rm -f "@OUT"
            log "日志为0字节 已删除"
        fi
        rm -f "@PIDF"
        exit 0
    """.trimIndent().replace("@", "$")

    data class Session(
        val pkg: String,
        val seconds: Int,
        val file: String,
        val runFile: String = Logcat.runLog(),
        val startedAt: Long = System.currentTimeMillis(),
    )

    /**
     * 把 apk 拷到私有目录里的固定副本
     *
     * 复制前先比一次：上一轮留下的副本如果大小和内容都一致就直接复用。
     * cp 是读 + 写，cmp 只读，命中时省掉一半 IO，一百多兆的包能省下可观的等待。
     */
    suspend fun stage(src: String, tmp: String): Boolean = withContext(Dispatchers.IO) {
        if (src.isBlank() || tmp.isBlank()) return@withContext false
        val s = Root.q(src)
        val t = Root.q(tmp)
        // 目标内容与源一致 → HIT；否则先删目标再拷，
        // 免得 cp 失败时 [ -f ] 把上一轮的旧包误判成成功
        val out = Root.exec(
            "if [ -f $t ] && [ -f $s ] && " +
                "[ \"\$(stat -c%s $s)\" = \"\$(stat -c%s $t)\" ] && cmp -s $s $t; then " +
                "echo HIT; " +
                "else " +
                "rm -f $t; cp $s $t 2>/dev/null; chmod 644 $t 2>/dev/null; " +
                "[ -f $t ] && echo COPY; " +
                "fi",
        )
        val hit = out.contains("HIT")
        val ok = hit || out.contains("COPY")
        AppLog.log(
            "测试",
            if (hit) {
                "复用上次的 ${java.io.File(tmp).name} 内容一致 跳过复制"
            } else {
                "复制 ${java.io.File(src).name} -> ${java.io.File(tmp).name} ${if (ok) "成功" else "失败"}"
            },
        )
        ok
    }

    /** 把待测 apk 拷到私有目录 失败返回 null */
    suspend fun stageTarget(src: String): String? =
        tmpTarget().takeIf { it.isNotBlank() && stage(src, it) }

    /** 把救砖 apk 拷到私有目录 失败返回 null */
    suspend fun stageRescue(src: String): String? =
        tmpRescue().takeIf { it.isNotBlank() && stage(src, it) }

    /**
     * 起一次测试
     *
     * @param targetTmp 私有目录里的待测 apk 副本 空则跳过安装
     * @param rescueTmp 私有目录里的救砖 apk 副本 空则只抓日志不救砖
     * @param targetSrc 待测 apk 的真实路径 只用于运行日志展示
     * @param rescueSrc 救砖 apk 的真实路径 只用于运行日志展示
     * @return 起不来（脚本没写出 pid）返回 null
     */
    suspend fun start(
        pkg: String,
        waitSeconds: Int,
        seconds: Int,
        targetTmp: String,
        rescueTmp: String,
        targetSrc: String = "",
        rescueSrc: String = "",
    ): Session? = withContext(Dispatchers.IO) {
        val target = workDir()
        if (target.isBlank()) return@withContext null
        val SCRIPT = scriptFile()
        val PID_FILE = pidFile()
        val RUN_LOG = runLog()
        val file = "$target/logcat_${safeName(pkg)}_${stamp()}.log"
        val b64 = Base64.encodeToString(SCRIPT_BODY.toByteArray(), Base64.NO_WRAP)
        val cmd = buildString {
            append("mkdir -p ${Root.q(target)}; ")
            append("echo '$b64' | base64 -d > $SCRIPT 2>/dev/null; ")
            append("chmod 755 $SCRIPT; ")
            // 上一轮没停干净的进程先收掉 不然两份 logcat 一起写
            append("p=\$(cat $PID_FILE 2>/dev/null); ")
            append("[ -n \"\$p\" ] && kill \"\$p\" 2>/dev/null; ")
            append("rm -f $PID_FILE; ")
            append("if command -v setsid >/dev/null 2>&1; then ")
            append("(setsid sh $SCRIPT ${Root.q(file)} ${Root.q(RUN_LOG)} $PID_FILE ")
            append("${Root.q(pkg)} $waitSeconds $seconds ")
            append("${Root.q(targetTmp)} ${Root.q(rescueTmp)} ")
            append("${Root.q(targetSrc)} ${Root.q(rescueSrc)}")
            append(" >/dev/null 2>&1 &); ")
            append("else ")
            append("(nohup sh $SCRIPT ${Root.q(file)} ${Root.q(RUN_LOG)} $PID_FILE ")
            append("${Root.q(pkg)} $waitSeconds $seconds ")
            append("${Root.q(targetTmp)} ${Root.q(rescueTmp)} ")
            append("${Root.q(targetSrc)} ${Root.q(rescueSrc)}")
            append(" >/dev/null 2>&1 &); ")
            append("fi; ")
            append("sleep 0.4; cat $PID_FILE 2>/dev/null")
        }
        val pid = Root.exec(cmd).trim().lines().lastOrNull()?.trim().orEmpty()
        AppLog.log("测试", "启动 $pkg 等待${waitSeconds}s 抓${seconds}s -> $file pid=$pid")
        if (pid.toIntOrNull() == null) return@withContext null
        Session(pkg = pkg, seconds = seconds, file = file)
    }

    /** 结束当前测试：先 TERM 让脚本自己收掉 logcat 子进程 不行再 -9 */
    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        val PID_FILE = pidFile()
        val pid = Root.exec("cat $PID_FILE 2>/dev/null").trim()
        if (pid.isBlank()) return@withContext false
        Root.exec(
            "kill $pid 2>/dev/null; sleep 0.4; kill -9 $pid 2>/dev/null; rm -f $PID_FILE",
        )
        AppLog.log("测试", "停止 pid=$pid")
        true
    }

    /** 还活着就返回 pid */
    suspend fun runningPid(): String? = withContext(Dispatchers.IO) {
        val PID_FILE = pidFile()
        val out = Root.exec(
            "p=\$(cat $PID_FILE 2>/dev/null); " +
                "[ -n \"\$p\" ] && kill -0 \"\$p\" 2>/dev/null && echo \"\$p\"",
        )
        out.trim().ifBlank { null }
    }

    /** 一次拿齐运行状态 / 抓到的日志大小 / 运行日志尾部 少跑两趟 su */
    data class Poll(val running: Boolean, val size: Long, val text: String)

    suspend fun poll(file: String, lines: Int = 300): Poll = withContext(Dispatchers.IO) {
        if (file.isBlank()) return@withContext Poll(false, 0L, "")
        val PID_FILE = pidFile()
        val RUN_LOG = runLog()
        val cmd = "p=\$(cat $PID_FILE 2>/dev/null); " +
            "if [ -n \"\$p\" ] && kill -0 \"\$p\" 2>/dev/null; " +
            "then echo RUN; else echo STOP; fi; " +
            "stat -c%s ${Root.q(file)} 2>/dev/null | head -1; " +
            "tail -n $lines ${Root.q(RUN_LOG)} 2>/dev/null"
        val ls = Root.exec(cmd).lines()
        Poll(
            running = ls.firstOrNull()?.trim() == "RUN",
            size = ls.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L,
            text = ls.drop(2).joinToString("\n"),
        )
    }

    suspend fun sizeOf(file: String): Long = withContext(Dispatchers.IO) {
        if (file.isBlank()) return@withContext 0L
        Root.exec("stat -c%s ${Root.q(file)} 2>/dev/null").trim().toLongOrNull() ?: 0L
    }

    /** 包名里可能出现 : 这种子进程后缀 文件名里得换掉 */
    private fun safeName(pkg: String): String =
        pkg.ifBlank { "all" }.replace(':', '_').replace('/', '_').replace(' ', '_')

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}

/**
 * 主页的测试配置：目标包名 / 等待时长 / 抓取时长 / 自动替换 / 两份 apk
 *
 * 只存在 App 自己这边 不写进模块的 settings.conf
 * 模块脚本跑的是救砖守护 主页跑的是自己这份测试脚本 两边互不干扰
 *
 * 注意目标包名和救砖 / 待测 apk 的包名没有关系：
 * 测的可能是插件 崩溃日志会落在宿主进程的包名里 所以这里允许随便填
 */
object LogConfig {
    var pkg by mutableStateOf(Logcat.DEFAULT_PKG)

    /** 主页自己的「自动替换」开关 和模块的 LOG_MODE 完全无关 */
    var autoReplace by mutableStateOf(false)

    /**
     * 两份 apk 的私有副本 选完就落在 files/apk 下
     * 只有重新选的时候才覆盖 平时不动 开跑时才拷到 files/test/ 的固定副本
     */
    var rescuePath by mutableStateOf("")
    var rescueSize by mutableStateOf(0L)
    var targetPath by mutableStateOf("")
    var targetSize by mutableStateOf(0L)
    var waitSeconds by mutableIntStateOf(Logcat.DEFAULT_WAIT)
    var seconds by mutableIntStateOf(Logcat.DEFAULT_SECONDS)

    private const val PREFS = "log_config"

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        pkg = p.getString("pkg", Logcat.DEFAULT_PKG).orEmpty()
        waitSeconds = p.getInt("wait", Logcat.DEFAULT_WAIT)
            .coerceIn(Logcat.MIN_WAIT, Logcat.MAX_WAIT)
        seconds = p.getInt("seconds", Logcat.DEFAULT_SECONDS)
            .coerceIn(Logcat.MIN_SECONDS, Logcat.MAX_SECONDS)
        autoReplace = p.getBoolean("auto_replace", false)
        rescuePath = p.getString("rescue_path", "").orEmpty()
        rescueSize = p.getLong("rescue_size", 0L)
        targetPath = p.getString("target_path", "").orEmpty()
        targetSize = p.getLong("target_size", 0L)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString("pkg", pkg)
            putInt("wait", waitSeconds)
            putInt("seconds", seconds)
            putBoolean("auto_replace", autoReplace)
            putString("rescue_path", rescuePath)
            putLong("rescue_size", rescueSize)
            putString("target_path", targetPath)
            putLong("target_size", targetSize)
        }.apply()
    }
}

/**
 * 主页两份 apk 的存放策略（和「界面」页对齐）
 *
 * 救砖：搬进 files/rescueapk/，**保留原文件名**；每次换包先把这个目录清空，
 *       保证随时只有一份「当前的救砖包」，反复选不会越存越多。
 * 待测：不复制。只记下 files/picked/ 那份的路径，开跑时才由 [Logcat.stage]
 *       拷到 files/test/ 的固定副本 —— 跟界面页写 TARGET_APK_PATH 是一样的思路。
 */
object ApkStore {

    /** 救砖 apk 的私有目录 */
    const val RESCUE_DIR = "rescueapk"

    /** 老版本放副本的地方（files/apk/rescue.apk）换了新目录后要顺手清掉 */
    private const val LEGACY_DIR = "apk"

    fun rescueDir(context: Context): java.io.File =
        java.io.File(context.filesDir, RESCUE_DIR).apply { mkdirs() }

    /**
     * 收下一个救砖 apk
     *
     * 先落位新的、再清旧的：搬移失败时上一份还在，配置不会指向一个空槽。
     * 源文件在 files/picked/ 下，和 files/rescueapk/ 同分区，rename 基本是瞬时的；
     * 万一跨了挂载点才退回复制。
     *
     * @param previousPath 上一份救砖包的路径，若还留在应用私有目录里一并删掉
     *                     （老版本那份在 files/apk/，不清会一直占着几百兆）
     */
    fun installRescue(
        context: Context,
        src: java.io.File,
        previousPath: String,
    ): java.io.File? = runCatching {
        val dir = rescueDir(context)
        val size = src.length()
        if (size <= 0L) return@runCatching null

        val dst = java.io.File(dir, src.name)
        // 同名旧包必须先让位，rename / 复制才能落下去
        if (dst.exists()) dst.delete()

        val moved = src.renameTo(dst)
        if (!moved) {
            src.inputStream().use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
            src.delete()
        }
        // 落地后再核一次大小：空间不足时复制可能「看起来成功」
        if (dst.length() != size) {
            dst.delete()
            return@runCatching null
        }

        // 新包已经就位，把同目录里别的旧包清掉
        dir.listFiles()?.forEach { f -> if (f.absolutePath != dst.absolutePath) f.delete() }

        val prev = java.io.File(previousPath)
        if (previousPath.isNotBlank() && previousPath != dst.absolutePath &&
            prev.isFile && isPrivate(context, prev)
        ) {
            prev.delete()
        }

        // 老目录空了就一起收掉 免得留个空文件夹
        java.io.File(context.filesDir, LEGACY_DIR)
            .takeIf { it.isDirectory && it.listFiles().isNullOrEmpty() }
            ?.delete()

        dst
    }.getOrNull()

    /** 只删自己地盘里的文件 用户手选的路径一律不动 */
    private fun isPrivate(context: Context, f: java.io.File): Boolean {
        val path = f.absolutePath
        return listOfNotNull(
            context.filesDir?.absolutePath,
            context.getExternalFilesDir(null)?.absolutePath,
        ).any { path.startsWith("$it/") }
    }

    /**
     * 待测换人时清掉上一份
     *
     * 待测现在只记路径，上一份若还躺在 files/picked/ 或老目录里，删掉不心疼 ——
     * 源文件在用户自己的目录里，我们删的只是当初的落地副本。
     */
    fun purgeTarget(context: Context, previousPath: String, keepPath: String) {
        if (previousPath.isBlank() || previousPath == keepPath) return
        val prev = java.io.File(previousPath)
        if (prev.isFile && isPrivate(context, prev)) prev.delete()
    }

    /** 比对两份副本是不是同一个文件（按内容算 不看文件名） */
    fun sameContent(a: String, b: String): Boolean = runCatching {
        if (a.isBlank() || b.isBlank()) return@runCatching false
        val fa = java.io.File(a)
        val fb = java.io.File(b)
        if (!fa.isFile || !fb.isFile) return@runCatching false
        if (fa.length() != fb.length()) return@runCatching false
        // apk 动辄上百兆 流式比对别整个读进内存
        java.io.BufferedInputStream(fa.inputStream()).use { ia ->
            java.io.BufferedInputStream(fb.inputStream()).use { ib ->
                val ba = ByteArray(64 * 1024)
                val bb = ByteArray(64 * 1024)
                while (true) {
                    val na = ia.readNBytes(ba, 0, ba.size)
                    val nb = ib.readNBytes(bb, 0, bb.size)
                    if (na != nb) return@runCatching false
                    if (na <= 0) return@runCatching true
                    if (!ba.copyOf(na).contentEquals(bb.copyOf(nb))) return@runCatching false
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
        }
    }.getOrDefault(false)
}
