package com.xkspeed.HyperMount.core

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * root 命令执行
 *
 * 用 libsu（topjohnwu Magisk 作者）的常驻 root shell：
 * 第一次走 su 授权弹窗 之后所有命令复用同一个 shell 进程
 * 不必每条命令都 fork 一次 su（脚本轮询状态下这个差异很明显）
 *
 * 所有方法都会阻塞到命令返回 **必须**在 IO 线程调用 见 [execAsync]
 */
object Root {

    /** 模块目录 模块 id 固定为 systemui_mount  */
    const val MODULE_DIR = "/data/adb/modules/systemui_mount"

    /** 救砖文件（固定在模块根 action.sh 只认这个名字） */
    const val RESCUE_APK = "$MODULE_DIR/MiuiSystemUI.apk"

    /** 挂载源（救砖时写这个文件） */
    const val AIM_APK = "$MODULE_DIR/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk"

    const val ACTION_SH = "$MODULE_DIR/action.sh"
    const val CONF = "$MODULE_DIR/config/settings.conf"
    const val MODULE_PROP = "$MODULE_DIR/module.prop"

    /**
     * 模块脚本目录
     *
     * 新版模块叫 scripts，v1.5.6 及更早还是 webroot。用户先更新 App 再更新模块
     * （或者反过来）时两边目录名会对不上，所以第一次用到时探测一次并缓存，不写死。
     */
    @Volatile
    private var scriptDirCache: String? = null

    fun scriptDir(): String =
        scriptDirCache ?: detectScriptDir().also { scriptDirCache = it }

    private fun detectScriptDir(): String {
        val out = exec(
            "if [ -f $MODULE_DIR/scripts/webctl.sh ]; then echo scripts; " +
                "elif [ -f $MODULE_DIR/webroot/webctl.sh ]; then echo webroot; fi"
        )
        return out.lineSequence().map { it.trim() }
            .lastOrNull { it == "scripts" || it == "webroot" } ?: "scripts"
    }

    /** 模块脚本目录下的某个脚本（webctl.sh / check.sh） */
    fun script(name: String): String = "$MODULE_DIR/${scriptDir()}/$name"

    /** 脚本的运行时状态目录（pid、日志都在这里） */
    fun workDir(): String = "$MODULE_DIR/${scriptDir()}/.work"

    /** 在 Application.onCreate 里调一次 必须在任何 Shell.cmd 之前  */
    fun init(context: android.content.Context) {
        // 脚本的 FAIL 提示可能走 stderr 合并进 stdout 才能一起判读。
        // libsu 6 里 FLAG_REDIRECT_STDERR 已废弃（设它只是打开这个开关），
        // 默认不并 stderr，所以要显式打开
        Shell.enableLegacyStderrRedirection = true
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                // 显式传 Context 省掉 libsu 反射找 Application 的兜底路径
                .setContext(context)
                // 复制几十兆 APK 在慢存储上可能要十几秒 留足余量
                // 注意是 long 参数 Kotlin 不会自动从 Int 拓宽
                .setTimeout(300L)
        )
    }

    /** 同步执行 返回合并后的输出（含 stderr） 异常不抛 转成"ERROR ..." 文本  */
    fun exec(cmd: String): String = try {
        val r = Shell.cmd(cmd).exec()
        buildString {
            r.out.forEach { appendLine(it) }
            if (!r.isSuccess) r.err.forEach { appendLine(it) }
        }.trim()
    } catch (t: Throwable) {
        "ERROR ${t.message ?: t.javaClass.simpleName}"
    }

    /** 后台线程执行（Compose 里直接 `rememberCoroutineScope().launch { ... }` 调用）  */
    suspend fun execAsync(cmd: String): String = withContext(Dispatchers.IO) { exec(cmd) }

    /** root 是否已授权 第一次调用会弹 su 授权框  */
    suspend fun available(): Boolean = withContext(Dispatchers.IO) {
        val out = exec("id -u")
        out.lineSequence().firstOrNull()?.trim() == "0"
    }

    /** 跑 webctl.sh 的子命令 */
    suspend fun webctl(args: String): String =
        execAsync("sh \"${script("webctl.sh")}\" $args")

    /** 给路径加 shell 引号（路径里有空格、括号、中文都不能出事） */
    fun q(path: String): String = "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
