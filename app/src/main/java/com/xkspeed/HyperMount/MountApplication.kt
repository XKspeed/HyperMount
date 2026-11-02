package com.xkspeed.HyperMount

import android.app.Application
import com.xkspeed.HyperMount.core.AppLog
import com.xkspeed.HyperMount.core.AppVersion
import com.xkspeed.HyperMount.core.BuildVariant
import com.xkspeed.HyperMount.core.Root
import com.xkspeed.HyperMount.ui.component.OverScrollTuning

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
        // 版本号走 PackageManager 读 先给它 context（界面与更新检查都要用）
        AppVersion.install(this)
        // 签名指纹判定正式版 / 测试包 决定设置里给不给调试入口
        BuildVariant.install(this)
        // 过滚动上限在 Compose 起来之前就要备好 否则第一帧会用默认值
        OverScrollTuning.load(this)
        AppLog.install(this)
        Root.init(this)
    }
}
