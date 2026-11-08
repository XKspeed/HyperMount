package com.xkspeed.HyperMount.core

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 更新包落地与安装。
 *
 * 包放在 App 专属的外部目录（filesDir 同级），写它不需要任何存储权限；
 * 装的时候通过 FileProvider 把 content:// 交给系统安装器。
 */
object Installer {

    /** 更新包存放目录 */
    fun updateDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "update").apply { mkdirs() }

    /**
     * 拉起系统安装界面。
     * 这里只负责把安装页叫出来，装不装、装完要不要重启由用户决定。
     */
    fun installApk(context: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        true
    }.getOrDefault(false)
}
