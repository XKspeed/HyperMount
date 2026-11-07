package com.xkspeed.HyperMount.core

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主页的测试流程
 *
 * 逻辑照搬「双兼容版 logcat.sh」：
 * 1. 后台先 pm install 待测 apk（装 SystemUI 要几秒 不能卡住主流程）
 * 2. 装完等 2 秒再开抓：安装期间那批 events（清数据 / dexopt / 装包回调）跟崩溃无关
 * 3. 只抓 crash 缓冲区（FATAL EXCEPTION 与 native tombstone）不抓 events
 * 4. 同时盯着文件大小 超过阈值就执行救砖
 * 5. 救砖直接用私有目录里的救砖包 pm install -r -d -t 最后重启 SystemUI
 *
 * 和模块守护完全解耦：脚本、pid、运行日志、抓到的日志全部落在 App 私有目录
 * files/test/ 下，不碰系统临时目录，卸载即清干净。
 * 脚本和 pid 放在应用缓存里 跑完即删 用户目录里不会留下 .sh
 * 抓到的 crash 日志写进设置里的日志目录 和模块日志放在一起
 *
 * 两份 apk 的策略不同：
 * 待测：一个字节都不复制，root 直接读用户选的原路径
 * 救砖：必须复制到 files/rescueapk/，只用这份副本——用户随时可能清下载目录，
 *       真崩了却找不到包就等于没有救砖；换包时替换掉旧的那份
 */
object Logcat {

    private var app: Context? = null

    /** start / stop 串行执行：两轮测试挨得太近时不能互相踩 PID 文件 */
    private val lifecycle = Mutex()

    /** Application.onCreate 里调一次 之后所有路径都基于私有目录 */
    fun install(context: Context) {
        app = context.applicationContext
        CacheConfig.load(context)
        LastRun.load(context)
        // 上一版会在 files/test 里留 apk 副本与脚本 这里顺手清掉
        // 现在的流程一个副本都不放 目录里只该剩运行流水
        runCatching {
            listOf(
                java.io.File(context.filesDir, "test"),
                java.io.File(context.cacheDir, "test"),
            ).forEach { dir ->
                dir.listFiles()?.forEach { f ->
                    val n = f.name
                    if (f.isFile && (n.endsWith(".apk") || n.endsWith(".sh"))) f.delete()
                }
            }
            // 上次异常退出留下的待测副本 平时留着给下次复用 只有超限才修剪
            pruneStage()
        }
    }

    /** 测试工作目录 files/test/ 只放运行流水 用户可见性低 */
    fun workDir(): String {
        val ctx = app ?: return ""
        val dir = java.io.File(ctx.filesDir, "test").apply { mkdirs() }
        return dir.absolutePath
    }

    /**
     * 待测 apk 的临时中转目录（cache/stage）
     *
     * 原文件不动：pm install 由 system_server 打开路径，直接读 /storage/emulated/0
     * 下的 fuse 文件会被 SELinux 拒（avc denied ... fuse）。这里先复制到 cache，
     * 脚本再用 `pm install -S` 从 stdin 喂给 pm，装完把副本删掉。
     */
    fun stageDir(): java.io.File? =
        app?.let { java.io.File(it.cacheDir, STAGE_DIR).apply { mkdirs() } }

    /** 脚本与 pid 走缓存目录 跑完删掉 不留在任何用户能翻到的目录里 */
    private fun cacheDir(): String {
        val ctx = app ?: return ""
        val dir = java.io.File(ctx.cacheDir, "test").apply { mkdirs() }
        return dir.absolutePath
    }

    private fun cachePath(name: String): String {
        val dir = cacheDir()
        return if (dir.isBlank()) "" else "$dir/$name"
    }

    private fun scriptFile(): String =
        cachePath("sui_test_${System.currentTimeMillis()}.sh")
    private fun pidFile(): String = cachePath("sui_test.pid")

    fun runLog(): String {
        val dir = workDir()
        return if (dir.isBlank()) "" else "$dir/sui_test.log"
    }

    const val DEFAULT_PKG = "com.android.systemui"

    /** 待测包是它时 额外盯界面的崩溃次数 / 日志疯长 / 堆栈占比 */
    const val PLUGIN_PKG = "miui.systemui.plugin"
    const val DEFAULT_SECONDS = 30
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 60

    /** 待测 apk 的缓存副本目录（装完不删 下次同内容直接复用） */
    const val STAGE_DIR = "stage"

    /** 按日志大小判定的阈值（KB）：1 到 10 之间调 */
    const val DEFAULT_SIZE_KB = 5
    const val MIN_SIZE_KB = 1
    const val MAX_SIZE_KB = 10

    /** @ 代表 $ 写脚本时不用一路转义 落盘前统一换回来 */
    private val SCRIPT_BODY: String = """
        #!/system/bin/sh
        # 抓崩溃日志 + 崩溃自动救砖
        # $1 日志输出 $2 运行日志 $3 pid 文件 $4 目标包名
        # $5 抓取秒 $6 待测 apk 副本 $7 救砖 apk 私有副本
        # $8 日志大小判定阈值（字节） 0 表示关闭
        # $9 1=plugin 增强判定（崩溃次数/日志疯长/堆栈占比）
        # $10 plugin 模式下累计崩溃几次触发（SystemUI fatal 行数）
        OUT="@1"
        RUN="@2"
        PIDF="@3"
        PKG="@4"
        DUR="@5"
        TARGET_APK="@6"
        RESCUE_APK="@7"
        SELF="@0"
        STATE="@(dirname "@RUN")/rescue_state"
        COUNT_FILE="@(dirname "@RUN")/rescue_count"
        RESULT_FILE="@(dirname "@RUN")/last_result"
        THRESHOLD="@8"
        case "@THRESHOLD" in ''|*[!0-9]*) THRESHOLD=0 ;; esac
        PLUGIN="@9"
        case "@PLUGIN" in 1) PLUGIN=1 ;; *) PLUGIN=0 ;; esac
        FATAL_TH="@10"
        case "@FATAL_TH" in ''|*[!0-9]*|0) FATAL_TH=3 ;; esac
        FLOOD_BYTES=2097152
        if [ -n "@PKG" ]; then NAME="@PKG"; else NAME="全部进程"; fi
        GUARD=300
        RESULT=no_crash

        trap 'rm -f "@SELF"' EXIT
        mkdir -p "@(dirname "@RUN")" 2>/dev/null
        echo @@ > "@PIDF"
        : > "@OUT"
        : > "@RUN"
        exec >>"@RUN" 2>&1

        log() { echo "[@(date +%H:%M:%S)] @1"; }

        # 救砖守卫每轮重新计时：上一轮救过砖 不影响这一轮能不能再救
        # 只有本轮内已救过一次 才按 GUARD 拦住连环救砖
        rm -f "@STATE"

        # 日志目录写不进去就别静默跑一趟：宁可当场退出 留下一行原因
        OUTDIR=@(dirname "@OUT")
        if ! mkdir -p "@OUTDIR" 2>/dev/null; then
            log "日志目录建不出来: @OUTDIR"
            [ "@(cat "@PIDF" 2>/dev/null)" = "@@" ] && rm -f "@PIDF"
            log "结果: dir_unwritable"
            exit 3
        fi
        PROBE="@OUTDIR/.hm_probe_@@"
        if ! : > "@PROBE" 2>/dev/null; then
            log "日志目录不可写: @OUTDIR"
            [ "@(cat "@PIDF" 2>/dev/null)" = "@@" ] && rm -f "@PIDF"
            log "结果: dir_unwritable"
            exit 3
        fi
        rm -f "@PROBE"

        # 两路判定 任一命中就救：
        #   1) 按包名：crash 缓冲里的 Process:（Java 崩溃）/ name:（native 崩溃）
        #   2) 按大小：crash 日志超过阈值也算崩（兜住拿不到包名的机型）
        crash_hit() {
            [ -f "@LOG_FILE" ] || return 1
            if [ -n "@PKG" ]; then
                grep -qF "Process: @PKG" "@LOG_FILE" 2>/dev/null && return 0
                grep -qF "name: @PKG" "@LOG_FILE" 2>/dev/null && return 0
            fi
            if [ "@THRESHOLD" -gt 0 ]; then
                SZ=@(stat -c%s "@LOG_FILE" 2>/dev/null || echo 0)
                [ "@SZ" -gt "@THRESHOLD" ] && return 0
            fi
            return 1
        }

        # plugin 模式：只数界面自己的 fatal 特征行
        # 待测包崩了不一定影响界面 真正要救的是界面起不来
        fatal_lines() {
            [ -f "@LOG_FILE" ] || { echo 0; return; }
            _fc=@(grep -cE "Process: com.android.systemui|Fatal signal.*systemui|FATAL SIGNAL.*systemui|WATCHDOG KILLING|ANR in com.android.systemui|Process com.android.systemui has died" "@LOG_FILE" 2>/dev/null)
            [ -z "@_fc" ] && _fc=0
            echo "@_fc"
        }

        # 堆栈占比：StackOverflow 时日志几乎全是 at 行
        stack_flood() {
            [ -f "@LOG_FILE" ] || return 1
            _sz=@(stat -c%s "@LOG_FILE" 2>/dev/null || echo 0)
            [ "@_sz" -gt 1048576 ] || return 1
            _t=@(wc -l < "@LOG_FILE" 2>/dev/null)
            _s=@(grep -c "AndroidRuntime: 	at " "@LOG_FILE" 2>/dev/null)
            [ -z "@_t" ] && _t=0
            [ -z "@_s" ] && _s=0
            [ "@_t" -gt 0 ] || return 1
            [ "@_s" -gt 5000 ] || return 1
            [ @((_s * 100 / _t)) -gt 80 ] && return 0
            return 1
        }

        # 返回 0 成功 / 1 失败 / 2 距上次救砖太近 跳过 / 3 救砖包不在
        rescue() {
            if [ ! -f "@RESCUE_APK" ]; then
                log "救砖apk不存在 跳过"
                return 3
            fi
            LAST=@(cat "@STATE" 2>/dev/null || echo 0)
            case "@LAST" in ''|*[!0-9]*) LAST=0 ;; esac
            NOW=@(date +%s)
            LEFT=@((NOW - LAST))
            if [ "@LEFT" -lt "@GUARD" ]; then
                log "距上次救砖 @LEFT 秒 未满 @GUARD 秒 跳过"
                return 2
            fi
            log "开始执行救砖..."
            SZ=@(stat -c%s "@RESCUE_APK" 2>/dev/null || echo 0)
            if [ "@SZ" -gt 0 ]; then
                R=@(pm install -r -d -t -S "@SZ" < "@RESCUE_APK" 2>&1)
            else
                R=@(pm install -r -d -t "@RESCUE_APK" 2>&1)
            fi
            RC=@?
            if [ "@RC" -eq 0 ]; then
                @(date +%s) > "@STATE"
                CNT=@(cat "@COUNT_FILE" 2>/dev/null || echo 0)
                echo @((CNT + 1)) > "@COUNT_FILE"
                log "救砖成功 (exit=0) 重启 SystemUI"
                (sleep 3; killall com.android.systemui 2>/dev/null) &
                return 0
            fi
            log "救砖失败 (exit=@RC): @R"
            return 1
        }

        watch_crash() {
            log "崩溃监控已启动 目标: @NAME 大小阈值: @THRESHOLD 字节"
            [ "@PLUGIN" = "1" ] && log "plugin 增强判定已开 崩溃 @FATAL_TH 次 / 日志疯长 / 堆栈占比"
            END=@(( @(date +%s) + DUR ))
            LAST_CNT_SIZE=-1
            LAST_SIZE=-1
            FLOOD=0
            STACK_TICK=0
            while [ @(date +%s) -lt "@END" ]; do
                HIT=""
                _now=@(stat -c%s "@LOG_FILE" 2>/dev/null || echo 0)
                if crash_hit; then
                    HIT="@NAME 崩溃"
                elif [ "@PLUGIN" = "1" ]; then
                    # 1) 累计崩溃次数：界面反复重启也算
                    if [ "@_now" != "@LAST_CNT_SIZE" ]; then
                        _cnt=@(fatal_lines)
                        LAST_CNT_SIZE="@_now"
                        [ "@_cnt" -ge "@FATAL_TH" ] && HIT="界面累计崩溃 @_cnt 次"
                    fi
                    # 2) 日志疯长：黑屏卡死时 PID 不变 只有日志在涨
                    [ "@LAST_SIZE" -lt 0 ] && LAST_SIZE="@_now"
                    _growth=@((_now - LAST_SIZE))
                    if [ "@_growth" -gt "@FLOOD_BYTES" ]; then
                        FLOOD=@((FLOOD + 1))
                    else
                        FLOOD=0
                    fi
                    LAST_SIZE="@_now"
                    [ -z "@HIT" ] && [ "@FLOOD" -ge 2 ] && HIT="日志疯长"
                    # 3) 堆栈占比：StackOverflow 特征 大文件扫起来慢 5 秒查一次
                    STACK_TICK=@((STACK_TICK + 1))
                    if [ -z "@HIT" ] && [ @((STACK_TICK % 5)) -eq 0 ] && stack_flood; then
                        HIT="堆栈占满"
                    fi
                fi
                if [ -n "@HIT" ]; then
                    log "检测到 @HIT 开始救砖"
                    rescue
                    RR=@?
                    case "@RR" in
                        0) RESULT=rescued ;;
                        1) RESULT=rescue_failed ;;
                        2) RESULT=crash ;;
                        3) RESULT=rescue_failed ;;
                    esac
                    echo "@RESULT" > "@RESULT_FILE"
                    log "救砖流程结束 日志继续抓到时间用完"
                    return 0
                fi
                sleep 1
            done
            log "监控时间到期"
        }

        LOG_FILE="@OUT"
        log "目标应用: @NAME"
        if [ -n "@RESCUE_APK" ]; then
            log "救砖apk: @RESCUE_APK"
        else
            log "未开启自动替换 只抓日志"
        fi

        echo no_crash > "@RESULT_FILE"
        # 装包前先清一次 crash 缓冲：上一轮留下的旧崩溃不能算进这次的结果
        logcat -c -b crash 2>/dev/null

        if [ -n "@TARGET_APK" ] && [ -f "@TARGET_APK" ]; then
            log "安装待测apk..."
            SZ=@(stat -c%s "@TARGET_APK" 2>/dev/null || echo 0)
            if [ "@SZ" -gt 0 ]; then
                R=@(pm install -r -d -t -S "@SZ" < "@TARGET_APK" 2>&1)
            else
                R=@(pm install -r -d -t "@TARGET_APK" 2>&1)
            fi
            RC=@?
            log "待测apk安装结束 (exit=@RC): @R"
        else
            log "没有待测apk 跳过安装"
        fi

        # 装完先静 2 秒再开抓：安装这一段系统在清旧数据 dexopt 拉起新进程
        # 早开一秒 文件前几百行就全是装包流水 真崩溃被埋在里面翻不出来
        # 这 2 秒内真崩了也没关系 还躺在缓冲里 开抓时一起落盘
        log "等待 2 秒后开始抓取"
        sleep 2

        OLD=@(pidof "@PKG" 2>/dev/null)
        if [ -n "@OLD" ]; then
            log "当前PID: @OLD"
        else
            log "当前PID: 无（未运行）"
        fi

        # 只读 crash 缓冲：FATAL EXCEPTION 与 native tombstone 都在这里
        # events 缓冲全是 system_server 的装包事件 跟崩溃无关 之前那些安装日志就是从这来的
        logcat -b crash -v threadtime -f "@OUT" &
        LPID=@!
        log "logcat PID: @LPID 开始抓 @DUR 秒"

        trap 'kill @LPID 2>/dev/null; [ -n "@MON" ] && kill @MON 2>/dev/null; [ "@(cat "@PIDF" 2>/dev/null)" = "@@" ] && rm -f "@PIDF"; exit 0' TERM INT

        MON=""
        if [ -n "@RESCUE_APK" ] && [ -f "@RESCUE_APK" ]; then
            watch_crash &
            MON=@!
        else
            log "没有救砖apk 跳过崩溃监控"
        fi

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
        log "抓取完成 大小: @((SIZE / 1024))KB"
        if [ "@SIZE" -eq 0 ]; then
            rm -f "@OUT"
            log "日志为0字节 已删除"
        fi

        CNT=@(cat "@COUNT_FILE" 2>/dev/null || echo 0)
        log "累计救砖次数: @CNT"
        RESULT=@(cat "@RESULT_FILE" 2>/dev/null || echo no_crash)
        log "结果: @RESULT"
        # 只删自己的 pid 文件：提前结束时新一轮可能已经写进去了
        [ "@(cat "@PIDF" 2>/dev/null)" = "@@" ] && rm -f "@PIDF"
        rm -f "@SELF"
        exit 0
    """.trimIndent().replace("@", "$")

    data class Session(
        val pkg: String,
        val seconds: Int,
        val file: String,
        val runFile: String = Logcat.runLog(),
        val startedAt: Long = System.currentTimeMillis(),
    )

    /** 待测 apk 进缓存的结果 */
    sealed interface StageResult {
        data class Ok(val path: String) : StageResult
        data class Fail(val reason: String) : StageResult
    }

    /** 起测试的结果：要么拿到会话，要么带一句能直接显示的原因 */
    sealed interface StartResult {
        data class Ok(val session: Session) : StartResult
        data class Fail(val reason: String) : StartResult
    }

    /**
     * 起一次测试
     *
     * 先探一次日志目录：写不进去就当场返回原因，不让脚本在后台静默跑一趟空的。
     *
     * @param targetApk 待测 apk 的 cache 副本 空则跳过安装
     * @param rescueApk 救砖 apk 的私有副本 空则只抓日志不救砖
     * @param dir 设置里的日志目录 抓到的 crash 日志写这里
     * @param pluginMode 待测包是 miui.systemui.plugin：额外开崩溃次数 / 日志疯长 / 堆栈占比三路判定
     * @param fatalThreshold plugin 模式下界面累计崩几次触发救砖
     */
    suspend fun start(
        pkg: String,
        seconds: Int,
        sizeLimitKb: Int,
        targetApk: String,
        rescueApk: String,
        pluginMode: Boolean,
        fatalThreshold: Int,
        dir: String,
    ): StartResult = withContext(Dispatchers.IO) {
        // 和 stop 串行：手动停止还没收完尾就点开始 会互相踩 PID 文件
        lifecycle.withLock {
            // 目录空着就不跑：兜底到隐藏目录 只会在用户不知道的地方堆一堆日志
            if (dir.isBlank()) return@withContext StartResult.Fail("未指定日志目录")
            val target = dir
            val SCRIPT = scriptFile()
            val PID_FILE = pidFile()
            val RUN_LOG = runLog()
            if (SCRIPT.isBlank() || PID_FILE.isBlank()) {
                return@withContext StartResult.Fail("应用私有目录不可用")
            }

            // 日志目录探针：logcat -f 写不进去不会报错 只会整轮 0 字节
            val probe = Root.exec(
                "mkdir -p ${Root.q(target)} 2>/dev/null; " +
                    ": > ${Root.q("$target/.hm_probe")} 2>/dev/null && echo PROBE_OK; " +
                    "rm -f ${Root.q("$target/.hm_probe")} 2>/dev/null",
            )
            if (!probe.contains("PROBE_OK")) {
                return@withContext StartResult.Fail("日志目录不可写 $target")
            }

            val file = "$target/logcat_${safeName(pkg)}_${stamp()}.log"
            val b64 = Base64.encodeToString(SCRIPT_BODY.toByteArray(), Base64.NO_WRAP)
            val cmd = buildString {
                append("mkdir -p ${Root.q(target)}; ")
                // 上一轮的脚本改成一跑一个文件名 旧文件删掉 不影响已经在跑的 sh
                append("rm -f ${Root.q(cacheDir())}/*.sh 2>/dev/null; ")
                append("echo '$b64' | base64 -d > $SCRIPT 2>/dev/null; ")
                append("chmod 755 $SCRIPT; ")
                // 上一轮没停干净的进程先收掉 不然两份 logcat 一起写
                append("p=\$(cat $PID_FILE 2>/dev/null); ")
                append("[ -n \"\$p\" ] && kill -9 \"\$p\" 2>/dev/null; ")
                append("if command -v pgrep >/dev/null 2>&1; then ")
                append("for q in \$(pgrep -f 'sui_test_.*\\.sh' 2>/dev/null); do ")
                append("[ \"\$q\" != \"\$\$\" ] && kill -9 \"\$q\" 2>/dev/null; done; fi; ")
                append("rm -f $PID_FILE; ")
                append("if command -v setsid >/dev/null 2>&1; then ")
                append("(setsid sh $SCRIPT ${Root.q(file)} ${Root.q(RUN_LOG)} $PID_FILE ")
                append("${Root.q(pkg)} $seconds ")
                append("${Root.q(targetApk)} ${Root.q(rescueApk)} ")
                append("${sizeLimitKb * 1024} ")
                append("${if (pluginMode) 1 else 0} ")
                append("${fatalThreshold.coerceAtLeast(1)}")
                append(" >/dev/null 2>&1 &); ")
                append("else ")
                append("(nohup sh $SCRIPT ${Root.q(file)} ${Root.q(RUN_LOG)} $PID_FILE ")
                append("${Root.q(pkg)} $seconds ")
                append("${Root.q(targetApk)} ${Root.q(rescueApk)} ")
                append("${sizeLimitKb * 1024} ")
                append("${if (pluginMode) 1 else 0} ")
                append("${fatalThreshold.coerceAtLeast(1)}")
                append(" >/dev/null 2>&1 &); ")
                append("fi; ")
                append("sleep 0.4; cat $PID_FILE 2>/dev/null")
            }
            val pid = Root.exec(cmd).trim().lines().lastOrNull()?.trim().orEmpty()
            AppLog.log(
                "测试",
                "启动 $pkg 抓${seconds}s 大小判定${sizeLimitKb}KB -> $file pid=$pid",
            )
            AppLog.i("脚本", "待测=$targetApk 救砖=${rescueApk.ifBlank { "(未启用)" }}")
            if (pid.toIntOrNull() == null) {
                return@withContext StartResult.Fail("脚本没有起来 检查 root 授权")
            }
            resetAppLogSync()
            StartResult.Ok(Session(pkg = pkg, seconds = seconds, file = file))
        }
    }

    /** 结束当前测试：先 TERM 让脚本自己收掉 logcat 子进程 不行再 -9 */
    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        // 和 start 串行：否则 stop 慢半拍会把新一轮的 PID 文件删掉 新一轮立刻“结束”
        lifecycle.withLock {
            val PID_FILE = pidFile()
            val pid = Root.exec("cat $PID_FILE 2>/dev/null").trim()
            if (pid.isBlank()) {
                pruneStage()
                return@withContext false
            }
            Root.exec(
                "kill $pid 2>/dev/null; sleep 0.4; kill -9 $pid 2>/dev/null; " +
                    // 文件里还是自己那个 pid 才删 新一轮已经写进去的话不能动
                    "[ \"\$(cat $PID_FILE 2>/dev/null)\" = \"$pid\" ] && rm -f $PID_FILE",
            )
            AppLog.log("测试", "停止 pid=$pid")
            // 脚本收尾时还会补几行 手动停止也要把它抄全
            runSynced = drainToAppLog(runLog(), runSynced, "脚本", "运行流水")
            // 副本保留给下次复用，只在超限时修剪
            pruneStage()
            true
        }
    }

    /**
     * 把待测 apk 复制到 cache/stage 再交给脚本
     *
     * 原位置不动、原文件不删。`pm install <路径>` 是 system_server 自己 fopen，
     * 读 /storage/emulated/0 下的 fuse 文件会被 SELinux 拒；复制到 cache 后由
     * root shell 用 `pm install -S` 从 stdin 喂给 pm。
     *
     * 副本装完**不删**：同大小且内容一致时直接复用，省一次上百兆的复制；
     * 目录超过 [CacheConfig] 里设的上限才修剪，只留最新那一份。
     *
     * 复制前先看剩余空间：空间不够时明确说还差多少，而不是丢一句「读不出来」。
     */
    suspend fun stageTarget(src: String): StageResult = withContext(Dispatchers.IO) {
        val ctx = app ?: return@withContext StageResult.Fail("应用私有目录不可用")
        if (src.isBlank()) return@withContext StageResult.Fail("还没有选待测apk")
        val dir = java.io.File(ctx.cacheDir, STAGE_DIR).apply { mkdirs() }
        val name = src.substringAfterLast('/').ifBlank { "target.apk" }
        val dst = java.io.File(dir, name)
        val tmp = java.io.File(dir, "$name.part")

        val srcSize = Root.exec("stat -c%s ${Root.q(src)} 2>/dev/null")
            .trim().toLongOrNull() ?: 0L
        if (srcSize <= 0L) {
            AppLog.e("测试", "待测apk读不到 $src")
            return@withContext StageResult.Fail("待测apk读不到 检查文件是否还在")
        }

        // 上一轮留下的同一份直接复用：先比大小 再比内容
        if (dst.isFile && dst.length() == srcSize) {
            val same = Root.exec(
                "cmp -s ${Root.q(src)} ${Root.q(dst.absolutePath)} && echo SAME",
            ).contains("SAME")
            if (same) {
                AppLog.i("测试", "待测apk复用缓存副本 ${dst.name} ${srcSize / 1024 / 1024}MB")
                pruneStage(dst)
                return@withContext StageResult.Ok(dst.absolutePath)
            }
        }

        // 复制前看空间：cache 分区放不下就别白复制一趟
        val free = runCatching { dir.usableSpace }.getOrDefault(0L)
        if (free in 1 until (srcSize + srcSize / 10)) {
            val need = Module.fmtSize(srcSize - free)
            AppLog.e("测试", "缓存空间不足 需要${Module.fmtSize(srcSize)} 可用${Module.fmtSize(free)}")
            return@withContext StageResult.Fail("存储空间不足 还差 $need")
        }

        dst.delete()
        tmp.delete()
        val uid = android.os.Process.myUid()
        // root 读 /storage 比 system_server 稳 复制完把归属改回 App
        val out = Root.exec(
            "cp -f ${Root.q(src)} ${Root.q(tmp.absolutePath)} 2>&1; " +
                "chown $uid:$uid ${Root.q(tmp.absolutePath)} 2>/dev/null; " +
                "chmod 600 ${Root.q(tmp.absolutePath)} 2>/dev/null; " +
                "stat -c%s ${Root.q(tmp.absolutePath)} 2>/dev/null",
        )
        val got = out.lineSequence().mapNotNull { it.trim().toLongOrNull() }.lastOrNull() ?: 0L
        if (got != srcSize) {
            AppLog.e("测试", "待测apk复制到缓存失败 期望${srcSize}B 实际${got}B\n$out")
            tmp.delete()
            return@withContext StageResult.Fail("复制到缓存失败 再试一次")
        }
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) {
            runCatching {
                tmp.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            }
            tmp.delete()
        }
        if (!dst.isFile || dst.length() != srcSize) {
            AppLog.e("测试", "待测apk缓存副本落盘失败 ${dst.absolutePath}")
            dst.delete()
            return@withContext StageResult.Fail("缓存副本落盘失败")
        }
        pruneStage(dst)
        AppLog.i("测试", "待测apk已复制到缓存 ${dst.name} ${srcSize / 1024 / 1024}MB 原路径不动")
        StageResult.Ok(dst.absolutePath)
    }

    /**
     * 修剪待测副本目录
     *
     * 平时一份都不删（下次同内容直接复用，省一次上百兆复制）；
     * 只有整个目录超过设定上限时，才把除「最新那份」以外的全清掉。
     * [keep] 传刚复制的副本，保证它一定留下；不传就按修改时间取最新的。
     */
    fun pruneStage(keep: java.io.File? = null) {
        runCatching {
            val dir = stageDir() ?: return
            val files = dir.listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            if (files.isEmpty()) return
            val total = files.sumOf { it.length() }
            // 0 = 无限制 不用修剪
            if (CacheConfig.limit <= 0L || total <= CacheConfig.limit) return
            val newest = keep?.takeIf { it.isFile } ?: files.first()
            AppLog.i(
                "测试",
                "缓存目录 ${total / 1024 / 1024}MB 超过上限 只保留 ${newest.name}",
            )
            files.forEach { f -> if (f.absolutePath != newest.absolutePath) f.delete() }
        }
    }

    /** 缓存目录里的 apk 按修改时间从新到旧 */
    fun stageFiles(): List<java.io.File> =
        stageDir()?.listFiles()?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** 缓存当前占用 */
    fun stageTotal(): Long = stageFiles().sumOf { it.length() }

    /** 删掉指定文件 返回释放的字节数 */
    fun deleteStage(targets: Collection<java.io.File>): Long {
        var freed = 0L
        var n = 0
        targets.forEach { f ->
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                freed += len
                n++
            }
        }
        if (freed > 0) AppLog.i("缓存", "删除 $n 个缓存 释放 ${Module.fmtSize(freed)}")
        return freed
    }

    /** 脚本累计救砖次数（每次成功 +1 写在 files/test/rescue_count） */
    suspend fun rescuedCount(): Int = withContext(Dispatchers.IO) {
        val f = runLog().substringBeforeLast('/') + "/rescue_count"
        Root.exec("cat ${Root.q(f)} 2>/dev/null").trim().toIntOrNull() ?: 0
    }

    /** 文件在不在 —— 走 root 判断，应用自己没存储权限时也能看外部路径 */
    suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext false
        Root.exec("[ -f ${Root.q(path)} ] && echo 1").contains("1")
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

    /** 已经抄进应用日志的字节数：只抄脚本自己的流水，抓到的 logcat 不重复抄 */
    private var runSynced = 0L

    /** 新一轮测试从零开始抄 免得把上一轮的尾巴再写一遍 */
    fun resetAppLogSync() {
        runSynced = 0L
    }

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
        val p = Poll(
            running = ls.firstOrNull()?.trim() == "RUN",
            size = ls.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L,
            text = ls.drop(2).joinToString("\n"),
        )
        // 只抄脚本自己的流水：抓到的 logcat 已经写进设置里的日志目录，不再重复抄一份
        runSynced = drainToAppLog(RUN_LOG, runSynced, "脚本", "运行流水")
        p
    }

    /**
     * 把文件里还没抄过的部分追加到应用日志
     *
     * 走 root 读 外部路径与被 root 写出来的文件都能拿到；按字节记位置，
     * 文件被脚本重写（变小）时从头再来，不会漏掉半截。
     */
    private fun drainToAppLog(path: String, offset: Long, tag: String, label: String): Long {
        if (path.isBlank()) return offset
        val from = offset.coerceAtLeast(0L)
        val cmd = "s=\$(stat -c%s ${Root.q(path)} 2>/dev/null || echo 0); echo \$s; " +
            "[ \"\$s\" -gt $from ] && tail -c +${from + 1} ${Root.q(path)} 2>/dev/null"
        val out = Root.exec(cmd)
        val first = out.lineSequence().firstOrNull()?.trim()?.toLongOrNull() ?: return offset
        val delta = out.substringAfter('\n', "")
        if (first < from) {
            // 文件被截断重写过 整份重抄
            return drainToAppLog(path, 0L, tag, label)
        }
        if (delta.isNotBlank()) {
            // 单次最多抄 8KB：抓到的 logcat 可能一次冒出几十 KB，全塞进 app.log 会把它撑爆
            val max = 8 * 1024
            val body = if (delta.length > max) {
                "…前 ${delta.length - max} 字符省略…\n" + delta.takeLast(max)
            } else {
                delta
            }
            AppLog.i(tag, "$label +${first - from} 字节\n${body.trimEnd()}")
        }
        return first
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
     * 两份 apk 的路径
     *
     * 待测：用户选的那份原文件，一个字节都不复制，root 直接装
     * 救砖：files/rescueapk/ 下的私有副本，选完即就位，之后只认这份
     */
    var rescuePath by mutableStateOf("")
    var rescueSize by mutableStateOf(0L)
    var targetPath by mutableStateOf("")
    var targetSize by mutableStateOf(0L)

    /** 待测 apk 自己的包名 用来判断是不是 miui.systemui.plugin */
    var targetPkg by mutableStateOf("")
    var seconds by mutableIntStateOf(Logcat.DEFAULT_SECONDS)

    /** 日志大小判定阈值（KB） 0 = 关闭 只按包名判定 */
    var sizeLimitKb by mutableIntStateOf(Logcat.DEFAULT_SIZE_KB)

    private const val PREFS = "log_config"

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        pkg = p.getString("pkg", Logcat.DEFAULT_PKG).orEmpty()
        seconds = p.getInt("seconds", Logcat.DEFAULT_SECONDS)
            .coerceIn(Logcat.MIN_SECONDS, Logcat.MAX_SECONDS)
        sizeLimitKb = p.getInt("size_kb", Logcat.DEFAULT_SIZE_KB)
            .coerceIn(Logcat.MIN_SIZE_KB, Logcat.MAX_SIZE_KB)
        autoReplace = p.getBoolean("auto_replace", false)
        rescuePath = p.getString("rescue_path", "").orEmpty()
        rescueSize = p.getLong("rescue_size", 0L)
        targetPath = p.getString("target_path", "").orEmpty()
        targetSize = p.getLong("target_size", 0L)
        targetPkg = p.getString("target_pkg", "").orEmpty()
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString("pkg", pkg)
            putInt("seconds", seconds)
            putInt("size_kb", sizeLimitKb)
            putBoolean("auto_replace", autoReplace)
            putString("rescue_path", rescuePath)
            putLong("rescue_size", rescueSize)
            putString("target_path", targetPath)
            putLong("target_size", targetSize)
            putString("target_pkg", targetPkg)
        }.apply()
    }
}

/**
 * 缓存副本的上限
 *
 * 设置页「缓存大小」调的就是这个值：目录超过它才修剪，只留最新那份 APK。
 */
object CacheConfig {
    const val MIN = 50L * 1024 * 1024
    const val MAX = 2L * 1024 * 1024 * 1024
    const val DEFAULT = 100L * 1024 * 1024

    /** 0 = 不限制：修剪时直接放行 目录里的副本一直留着 */
    const val UNLIMITED = 0L

    private const val PREFS = "cache_config"

    var limit by mutableStateOf(DEFAULT)
        private set

    fun load(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("limit", DEFAULT)
        limit = normalize(raw)
    }

    fun set(context: Context, value: Long) {
        limit = normalize(value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong("limit", limit).apply()
    }

    /** 0 原样保留成「无限制」 其余钳到可用区间 */
    private fun normalize(value: Long): Long =
        if (value <= 0L) UNLIMITED else value.coerceIn(MIN, MAX)
}

/**
 * 上一轮测试的结论
 *
 * SystemUI 反复重启时看不见界面，救砖到底成没成只能事后再看。
 * 这里只存一行结论，进主页时挂在状态卡下面。
 */
object LastRun {
    private const val PREFS = "last_run"

    var at by mutableStateOf(0L)
        private set
    var result by mutableStateOf("")
        private set
    var pkg by mutableStateOf("")
        private set

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        at = p.getLong("at", 0L)
        result = p.getString("result", "").orEmpty()
        pkg = p.getString("pkg", "").orEmpty()
    }

    fun save(context: Context, pkg: String, result: String) {
        at = System.currentTimeMillis()
        this.pkg = pkg
        this.result = result
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("at", at)
            .putString("result", result)
            .putString("pkg", pkg)
            .apply()
    }

    /** 结论的时间 只到分钟 */
    fun timeText(): String = if (at <= 0L) "" else
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(at))

    /** 结论翻成一句人话 */
    fun text(): String = when (result) {
        "rescued" -> "已自动救砖"
        "rescue_failed" -> "救砖失败"
        "crash" -> "检测到崩溃 未执行救砖"
        "no_crash" -> "未检测到崩溃"
        "dir_unwritable" -> "日志目录不可写"
        else -> ""
    }
}

/**
 * 主页两份 apk 的存放策略
 *
 * 救砖：从选择器的流直接写进 files/rescueapk/，**保留原文件名**；
 *       每次换包把目录里其它文件清掉，随时只有一份「当前的救砖包」。
 *       测试脚本只认这份副本，用户删了下载目录里的原文件也不影响救砖。
 * 待测：一个字节都不复制。只记下选择器给出的真实路径，开跑时脚本直接装它。
 */
object ApkStore {

    /** 救砖 apk 的私有目录 */
    const val RESCUE_DIR = "rescueapk"

    /** 老版本放副本的地方（files/apk/rescue.apk）换了新目录后要顺手清掉 */
    private const val LEGACY_DIR = "apk"

    fun rescueDir(context: Context): java.io.File =
        java.io.File(context.filesDir, RESCUE_DIR).apply { mkdirs() }

    /**
     * 从选择器返回的 uri 直接写进 files/rescueapk/
     *
     * 不再经 files/picked 中转：选择器给的是 content:// 流，读一次直接落到目标目录，
     * 保留原文件名。写临时名再改名，中途失败不会把上一份好的救砖包弄没。
     *
     * @param previousPath 上一份救砖包路径，新包就位后一并清掉
     */
    @Synchronized
    fun installRescueFromUri(
        context: Context,
        uri: android.net.Uri,
        previousPath: String,
    ): java.io.File? = runCatching {
        val dir = rescueDir(context)
        val raw = runCatching {
            context.contentResolver
                .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull().orEmpty()
        val name = sanitizeName(raw.ifBlank { "rescue.apk" })
        val dst = java.io.File(dir, name)
        val tmp = java.io.File(dir, "$name.part")

        tmp.delete()
        val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
        input.use { src -> tmp.outputStream().use { src.copyTo(it) } }
        if (tmp.length() <= 0L) {
            tmp.delete()
            return@runCatching null
        }

        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) {
            tmp.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            tmp.delete()
        }

        // 只写入，不清理：用户可能在确认弹窗里点取消，旧包得原样留着
        dst
    }.getOrNull()

    /**
     * 确认使用新包：把同目录残留和上一份清掉
     *
     * 取消选择时不调用，旧包和配置都保持原样。
     */
    @Synchronized
    fun commitRescue(context: Context, keep: java.io.File, previousPath: String) {
        val dir = rescueDir(context)
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.absolutePath != keep.absolutePath) f.delete()
        }
        val prev = java.io.File(previousPath)
        if (previousPath.isNotBlank() && previousPath != keep.absolutePath &&
            prev.isFile && isPrivate(context, prev)
        ) {
            prev.delete()
        }
    }

    /**
     * 把救砖包收敛到 files/rescueapk/ 下的唯一一份
     *
     * 待测可以靠 su 直接读原文件，救砖不行：用户随时可能把下载目录清掉，
     * 真崩了却找不到包就等于没有救砖。所以救砖只认私有副本，原文件仅作来源。
     *
     * 三种历史情况都在这里收口：
     * - 指向私有目录且文件还在 → 清掉同目录其它旧包，顺手同步大小
     * - 指向私有目录但文件没了 → 清空配置，让用户重选
     * - 指向外部原文件（旧版本留下的配置）→ 复制进私有目录并改指向副本，原文件不动
     *
     * @return 配置里是否有一份可用的救砖包
     */
    @Synchronized
    fun ensureRescue(context: Context): Boolean {
        if (LogConfig.rescuePath.isBlank()) return false
        val dir = rescueDir(context)
        val file = java.io.File(LogConfig.rescuePath)

        // 已经在私有目录里：只留这一份，其余都是换包时没清干净的残留
        if (isInDir(dir, file)) {
            if (!file.isFile || file.length() <= 0L) {
                clear(context)
                return false
            }
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.absolutePath != file.absolutePath) f.delete()
            }
            if (LogConfig.rescueSize != file.length()) {
                LogConfig.rescueSize = file.length()
                LogConfig.save(context)
            }
            return true
        }

        // 旧配置直接指向用户原文件：复制一份进私有目录，之后只认副本
        if (!file.isFile || file.length() <= 0L) {
            clear(context)
            return false
        }
        val dst = java.io.File(dir, sanitizeName(file.name))
        val tmp = java.io.File(dir, "${dst.name}.part")
        tmp.delete()

        // 先按普通文件读，权限不够再交给 root 复制（root 写出来的归属要改回 App）
        val direct = runCatching {
            file.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            tmp.length() == file.length()
        }.getOrDefault(false)
        if (!direct) {
            tmp.delete()
            val uid = android.os.Process.myUid()
            Root.exec(
                "cp -f ${Root.q(file.absolutePath)} ${Root.q(tmp.absolutePath)} && " +
                    "chown $uid:$uid ${Root.q(tmp.absolutePath)} && " +
                    "chmod 600 ${Root.q(tmp.absolutePath)}"
            )
        }
        if (!tmp.isFile || tmp.length() != file.length() || tmp.length() <= 0L) {
            tmp.delete()
            clear(context)
            return false
        }

        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) {
            runCatching {
                tmp.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            }
            tmp.delete()
        }
        if (!dst.isFile || dst.length() <= 0L) {
            dst.delete()
            clear(context)
            return false
        }

        dir.listFiles()?.forEach { f -> if (f.absolutePath != dst.absolutePath) f.delete() }
        LogConfig.rescuePath = dst.absolutePath
        LogConfig.rescueSize = dst.length()
        LogConfig.save(context)
        return true
    }

    /** 配置里的救砖包作废，界面回到「未选择」 */
    private fun clear(context: Context) {
        LogConfig.rescuePath = ""
        LogConfig.rescueSize = 0L
        LogConfig.save(context)
    }

    /** 判断文件是不是就在救砖目录里（防 .. 之类的路径花招） */
    private fun isInDir(dir: java.io.File, f: java.io.File): Boolean = runCatching {
        f.canonicalPath.startsWith(dir.canonicalPath + java.io.File.separator)
    }.getOrDefault(false)

    /** 文件名里的分隔符换掉 别让配置里那行被截断 */
    private fun sanitizeName(raw: String): String {
        val cleaned = raw.replace('/', '_').replace('\\', '_').replace(' ', '_')
        val tail = Regex("""\.apk\.\d+$""", RegexOption.IGNORE_CASE)
        val base = if (tail.containsMatchIn(cleaned)) tail.replace(cleaned, ".apk") else cleaned
        return if (base.endsWith(".apk", ignoreCase = true)) base else "$base.apk"
    }

    /** 只删自己地盘里的文件 用户手选的路径一律不动 */
    private fun isPrivate(context: Context, f: java.io.File): Boolean {
        val path = f.absolutePath
        return listOfNotNull(
            context.filesDir?.absolutePath,
            context.getExternalFilesDir(null)?.absolutePath,
        ).any { path.startsWith("$it/") }
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
