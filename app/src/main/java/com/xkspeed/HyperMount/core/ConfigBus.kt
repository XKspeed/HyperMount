package com.xkspeed.HyperMount.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 后台写配置后让界面重新读一次
 *
 * MCP 服务跑在独立线程，拿不到页面里那个 refresh()。
 * 写完之后把 [tick] 加一，页面监听到就重新 readState。
 */
object ConfigBus {
    var tick by mutableIntStateOf(0)
        private set

    fun bump() {
        tick++
    }
}
