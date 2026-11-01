package com.xkspeed.HyperMount

import android.app.Application
import com.xkspeed.HyperMount.core.AppLog
import com.xkspeed.HyperMount.core.Root

/**
 * libsu 的默认 builder 必须在任何 Shell.cmd 之前设置
 * 放在 Application.onCreate 是最早且唯一的时机
 *
 * 崩溃日志也在这里装：装得越早 启动早期的崩溃（ThemeState.load、
 * Root.init、Compose 首次组合）才抓得到
 */
class MountApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.install(this)
        Root.init(this)
    }
}
