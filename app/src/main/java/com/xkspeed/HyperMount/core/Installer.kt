package com.xkspeed.HyperMount.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StrictMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 更新包落地与安装。
 *
 * 下载先落在 App 自己的目录（写它不需要任何权限），下完用 root 搬到
 * [/sdcard/Download]：系统下载目录，安装器拿真实路径就能直接读。
 *
 * 装包一律交给系统安装器：`pm install` 在部分机型上装不上（签名校验 / 权限），
 * 用户还得再点一次 不如下完直接拉起安装器。
 */
object Installer {

    /** 系统下载目录：安装器只认这种真实路径 */
    const val PUBLIC_DIR = "/sdcard/Download"

    /** 下载暂存目录（App 专属，写它不需要权限） */
    fun stagingDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "update").apply { mkdirs() }

    /**
     * 把下好的包从暂存目录搬到公共目录。
     *
     * App 进程写不了 /sdcard/Download（Android 11 起），只能借 root 搬；
     * chmod 644 是给没 root 的安装器留一条读的路。
     *
     * @return 公共路径；失败返回 null
     */
    suspend fun publish(src: File, name: String): String? = withContext(Dispatchers.IO) {
        val dest = "$PUBLIC_DIR/$name"
        val out = Root.exec(
            "mkdir -p \"$PUBLIC_DIR\" && cp -f \"${src.absolutePath}\" \"$dest\" " +
                "&& chmod 644 \"$dest\" && echo HYPERMOUNT_CP_OK",
        )
        if (out.lineSequence().any { it.trim() == "HYPERMOUNT_CP_OK" }) dest else null
    }

    /**
     * 用真实路径（file://）拉起系统安装器。
     *
     * Android 7 起直接传 file:// 会抛 FileUriExposedException，
     * 先把 StrictMode 的这条检查关掉 —— 安装器只认这种路径。
     */
    fun openInstaller(context: Context, path: String): Boolean {
        // 不能拿 File.exists() 判：App 没有存储权限，/sdcard/Download 下 root 拷进去的包
        // 在它眼里根本不存在，一判就直接返回了。读文件的是安装器 不是我们。
        disableFileUriCheck()
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        Uri.fromFile(File(path)),
                        "application/vnd.android.package-archive",
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            true
        }.getOrDefault(false)
    }

    private fun disableFileUriCheck() {
        // 隐藏方法 反射拿不到（或哪天真被删了）就把 VM policy 换成只记日志的 一样不会崩
        val closed = runCatching {
            val m = StrictMode::class.java.getDeclaredMethod("disableDeathOnFileUriExposure")
            m.isAccessible = true
            m.invoke(null)
            true
        }.getOrDefault(false)
        if (!closed) {
            runCatching {
                StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().penaltyLog().build())
            }
        }
    }
}
