package com.xkspeed.HyperMount.ui.component.glass

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.xkspeed.HyperMount.ui.UiConfigState
import com.xkspeed.HyperMount.ui.util.isInDarkTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class GlassNavItem(val label: String, val icon: ImageVector)

/**
 * 底栏：液态玻璃胶囊
 *
 * - 玻璃开：容器玻璃（blur 4dp + 折射透镜 + 内阴影）+ 选中 tab 的玻璃胶囊可拖动
 * - 玻璃关（UiConfigState.enableBlur=false）：退化为磨砂胶囊 选中 tab 用主题色图标
 *
 * backdrop 必须是**页面内容挂了 layerBackdrop 的那一个** 否则采样不到滚动内容
 */
@Composable
fun GlassBottomNav(
    selectedIndex: () -> Int,
    onTabSelected: (Int) -> Unit,
    items: List<GlassNavItem>,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val dark = isInDarkTheme()
    // 玻璃面自适应亮度开着时内容色由它给；否则用主题静态色
    val contentColor = LocalGlassContentColor.current
        ?: if (dark) Color.White else Color.Black
    val accentColor = MiuixTheme.colorScheme.primary
    // 安全模式下彻底关掉玻璃（连 solid 磨砂也不走） 退回普通底栏
    val solidCapsule = !UiConfigState.enableBlur || UiConfigState.safeMode

    LiquidBottomTabs(
        selectedTabIndex = selectedIndex,
        onTabSelected = onTabSelected,
        backdrop = backdrop,
        tabsCount = items.size,
        solidCapsule = solidCapsule,
        modifier = modifier.fillMaxWidth(0.93f),
    ) {
        items.forEachIndexed { index, item ->
            LiquidBottomTab(onClick = { onTabSelected(index) }) {
                val selected = index == selectedIndex()
                val tint = if (solidCapsule && selected) accentColor else contentColor
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = tint,
                    modifier = Modifier.size(26.dp),
                )
                Text(
                    text = item.label,
                    color = tint,
                    fontSize = 12.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
