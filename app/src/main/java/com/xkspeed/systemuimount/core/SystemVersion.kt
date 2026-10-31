package com.xkspeed.systemuimount.core

import android.os.Build

/**
 * 系统版本指纹：只看「Android 版本」+「ROM 的具体版本」两项。
 *
 * 具体版本取 `ro.build.version.incremental`（HyperOS / MIUI 每次 OTA 都会变，
 * 形如 4.0.24.0.WOCCNXM）；ROM 自己的大版本号（os4.0 这种）不参与比较，
 * 它跨 OTA 不动，比它等于比不出来。音量键校验绑的是 `/dev/input/eventN`，
 * 跟着每次 ROM 更新都可能重排，所以要的是这种细粒度。
 */
object SystemVersion {

    /** 具体版本串（设置页/排障展示也用这个） */
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
     * 指纹 = 安卓版本 + 具体版本。
     * ROM 串读不到时只剩安卓版本，粒度粗一点但至少跨系统大版本还能触发一次重验。
     */
    val fingerprint: String =
        "${Build.VERSION.RELEASE}/${Build.VERSION.SDK_INT}|${if (usable(build)) build else "-"}"
}
