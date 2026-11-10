package com.xkspeed.HyperMount.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarResult

/**
 * 底部提示条
 *
 * 「开始测试」不再因为条件不满足就灰掉：按钮永远能点，点了再看缺什么，
 * 这里统一弹 4 秒的 Snackbar，需要用户动手的带一个 action 按钮。
 */
object UiFeedback {
    val host = SnackbarHostState()

    /** 弹一条 4 秒的提示；返回 true 表示用户点了右侧按钮 */
    suspend fun show(message: String, action: String? = null): Boolean {
        val result = host.showSnackbar(
            message = message,
            actionLabel = action,
            duration = SnackbarDuration.Custom(4000),
        )
        return result == SnackbarResult.ActionPerformed
    }

    /** 不需要 action 时用这个，省得每次都要判断返回值 */
    suspend fun toast(message: String) {
        show(message)
    }
}

/** 拦截提示能带用户去的位置：底栏页 + 页面里的哪一行（不是整张卡） */
enum class LocateTarget(val tab: Int) {
    /** 系统界面页 · 文件卡：救砖界面行 */
    HOME_RESCUE(0),

    /** 系统界面页 · 文件卡：待测界面行 */
    HOME_TARGET(0),

    /** 应用页 · 文件卡：救砖 apk 行 */
    MAIN_RESCUE(1),

    /** 应用页 · 文件卡：待测 apk 行 */
    MAIN_TARGET(1),

    /** 设置页 · 运行卡：日志目录行 */
    LOG_DIR(2),
}

/**
 * 一次定位请求
 *
 * 拦截时记下要去哪，主框架负责切页，页面负责滚到位并点亮卡片。
 * [flash] 每请求一次加一，卡片拿它当 key 触发一次描边动画。
 */
object Locate {
    var pending by mutableStateOf<LocateTarget?>(null)
        private set

    var flashTarget by mutableStateOf<LocateTarget?>(null)
        private set

    var flash by mutableIntStateOf(0)
        private set

    fun request(target: LocateTarget) {
        pending = target
    }

    /** 页面已经滚到位 */
    fun consume() {
        pending = null
    }

    /** 点亮卡片：2 秒渐隐 */
    fun hint(target: LocateTarget) {
        flashTarget = target
        flash++
    }

    /** 高亮播完就清掉，免得之后重新组合那一页时又闪一次 */
    fun clear(target: LocateTarget) {
        if (flashTarget == target) flashTarget = null
    }
}
