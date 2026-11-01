package com.xkspeed.HyperMount.core

import android.content.Context
import android.content.pm.PackageManager
import com.xkspeed.HyperMount.BuildConfig

/**
 * 运行时读到的版本号：以已安装包的 AndroidManifest 为准。
 *
 * [BuildConfig] 里的 VERSION_NAME / VERSION_CODE 是编译时写进 dex 的常量，
 * 用 MT 管理器改 AndroidManifest 的版本号 / 版本代号之后那边不会变，
 * 测更新流程就分不出装的是哪一版。界面与更新检查统一走这里，
 * 读到的永远是当前安装包的真实版本；只有读不到时才退回 BuildConfig。
 */
object AppVersion {
    @Volatile
    private var appContext: Context? = null

    /** 在 Application.onCreate 里调一次 早于任何界面与更新检查 */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    val name: String get() = versionName() ?: BuildConfig.VERSION_NAME
    val code: Long get() = versionCode() ?: BuildConfig.VERSION_CODE.toLong()

    @Volatile
    private var cached: android.content.pm.PackageInfo? = null

    private fun info(): android.content.pm.PackageInfo? = cached ?: runCatching {
        val ctx = appContext ?: return null
        ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0L))
    }.getOrNull()?.also { cached = it }

    private fun versionName(): String? = info()?.versionName?.takeIf { it.isNotBlank() }
    private fun versionCode(): Long? = info()?.longVersionCode
}
