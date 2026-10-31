package com.xkspeed.systemuimount.core

import android.os.Build

/**
 * 系统版本指纹：认的是 ROM 自己的 OTA 版本串，不是 Android 大版本号。
 *
 * HyperOS / MIUI 每推一版 OTA 都会改 `ro.build.version.incremental` / `ro.build.display`
 * （如 4.0.24.0.WOCCNXM、V816.0.6.0.UMCCNXM），而 Android 大版本
 * （os4.0 / Android 17 这种）一年才动一次。音量键校验绑的是 `/dev/input/eventN`，
 * ROM 每次更新都可能重排输入设备，所以拿大版本号比对等于永远比不出变化。
 */
object SystemVersion {

    /** 具体版本串（设置页展示 / 排障用）：增量版本优先，其次显示版本 */
    val build: String = run {
        val incremental = Build.VERSION.INCREMENTAL.orEmpty().trim()
        val display = Build.DISPLAY.orEmpty().trim()
        when {
            usable(incremental) -> incremental
            usable(display) -> display
            else -> ""
        }
    }

    private fun usable(value: String): Boolean =
        value.isNotEmpty() && !value.equals("unknown", ignoreCase = true)

    /**
     * 完整指纹。两个 ROM 版本串都带上（有的机型的 OTA 串只落在其中一个），
     * 再兜一层安全补丁 / Android 版本 / API：ROM 串都读不到时至少 OTA 与系统大版本还能带动它变。
     */
    val fingerprint: String = buildString {
        append(if (usable(build)) build else "-")
        append('|').append(Build.DISPLAY.orEmpty().trim())
        append('|').append(Build.VERSION.SECURITY_PATCH.orEmpty())
        append('|').append(Build.VERSION.RELEASE)
        append('|').append(Build.VERSION.SDK_INT)
    }
}
