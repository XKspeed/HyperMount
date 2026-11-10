package com.xkspeed.HyperMount.ui.component.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xkspeed.HyperMount.ui.UiConfigState
import com.xkspeed.HyperMount.ui.util.stableNavigationBarHeight

/**
 * 底栏顶边到屏幕底的距离（含系统导航条）
 *
 * 两种底栏高度不一样：标准条 64dp 贴底自己吃 inset，液态玻璃悬浮条 70dp 外面再垫 inset，
 * 可见顶边还要再让 6dp 呼吸。任何「贴着底栏上方」的东西（操作卡、Snackbar、列表底部留白）
 * 都得用这个实测值，而不是各写各的 `64.dp` / `70.dp`，否则换底栏样式就会错位。
 */
object BottomBarMetrics {
    /** 0 表示还没量到（第一帧），调用方走 [bottomBarTopOffset] 的兜底公式 */
    var topFromBottom by mutableStateOf(0.dp)
}

/** 挂在底栏外层容器上：把「顶边到屏幕底」量出来写进 [BottomBarMetrics] */
@Composable
fun Modifier.trackBottomBarTop(): Modifier {
    val density = LocalDensity.current
    return onGloballyPositioned { coords ->
        val parent = coords.parentLayoutCoordinates ?: return@onGloballyPositioned
        val bottomPx = parent.size.height - coords.positionInParent().y
        BottomBarMetrics.topFromBottom = with(density) { bottomPx.toDp() }
    }
}

/** 底栏顶边到屏幕底的稳定值：还没量到时按当前底栏样式估算，量到后以实测为准 */
@Composable
fun bottomBarTopOffset(): Dp {
    val measured = BottomBarMetrics.topFromBottom
    if (measured > 0.dp) return measured
    val barHeight = if (UiConfigState.iosBottomBar) 70.dp else StandardBottomNavHeight
    return stableNavigationBarHeight() + barHeight
}
