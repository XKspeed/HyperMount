package com.xkspeed.HyperMount.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarResult

/**
 * 底部提示条
 *
 * 「开始测试」不再因为条件不满足就灰掉：按钮永远能点，点了再看缺什么，
 * 这里统一弹一条提示，需要用户动手的带一个 action 按钮（比如「撤销」）。
 */
object UiFeedback {
    val host = SnackbarHostState()

    /**
     * 当前展开着的底部面板数量。
     *
     * 面板是独立 window（Dialog），页面层画的任何东西都会被它整个盖住 ——
     * 所以面板开着时页面那处宿主不再组合，改由面板自己窗口里的那份接力。
     */
    private var sheetCount by mutableIntStateOf(0)

    val sheetOpen: Boolean get() = sheetCount > 0

    fun onSheetShown() {
        sheetCount++
    }

    fun onSheetHidden() {
        if (sheetCount > 0) sheetCount--
    }

    /**
     * 弹一条提示；返回 true 表示用户点了右侧按钮。
     *
     * 带按钮的多留一会儿（6 秒），够按；不带按钮 3 秒。
     */
    suspend fun show(message: String, action: String? = null): Boolean {
        val result = host.showSnackbar(
            message = message,
            actionLabel = action,
            duration = SnackbarDuration.Custom(if (action != null) 6000 else 3000),
        )
        return result == SnackbarResult.ActionPerformed
    }

    /** 不需要 action 时用这个，省得每次都要判断返回值 */
    suspend fun toast(message: String) {
        show(message)
    }
}

/**
 * 页面层的提示条宿主
 *
 * 只在没有底部面板时挂。位置由调用方给（贴在悬浮底栏 + 操作卡上方），
 * 宽度整行，样式就是库自带的 Snackbar（底色重、强调）。
 */
@Composable
fun FeedbackOverlay(modifier: Modifier = Modifier) {
    if (UiFeedback.sheetOpen) return
    SnackbarHost(state = UiFeedback.host, modifier = modifier.fillMaxWidth())
}

/**
 * 底部面板里的提示条宿主
 *
 * 和页面层共用同一块状态，但画在面板自己的窗口里 —— 面板是独立 window，
 * 页面那份压不过它，只能在面板内部再挂一份。面板内容滚到底也盖不住它。
 */
@Composable
fun SheetFeedback(modifier: Modifier = Modifier) {
    SnackbarHost(state = UiFeedback.host, modifier = modifier.fillMaxWidth())
}

/** 拦截提示能带用户去的位置：底栏页 + 页面里的哪一行（不是整张卡） */
enum class LocateTarget(val tab: Int) {
    /** 系统界面页 · 文件卡：救砖界面行 */
    HOME_RESCUE(0),

    /** 系统界面页 · 文件卡：待测界面行 */
    HOME_TARGET(0),

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
