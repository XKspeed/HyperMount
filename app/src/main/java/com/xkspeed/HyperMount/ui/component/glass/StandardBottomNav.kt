package com.xkspeed.HyperMount.ui.component.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.xkspeed.HyperMount.ui.UiConfigState

/**
 * 标准底栏：Miuix 原生 [NavigationBar]，整条贴底、不做液态玻璃胶囊
 *
 * 材质逐值照 miuix-example 的标准底栏（`AppContent.kt` 的 `NavigationBar` 分支）：
 * - 背后 `textureBlur(25f)`，颜色作为 `blurColors` 的 blendColor 合进模糊层（surface 80%）
 * - **不是**压一层 `background`：那会在模糊之上再盖一遍色，配上自带的高光就是一圈白边
 * - 关掉模糊（安全模式 / 拿不到 backdrop）时退回实心 surface
 *
 * 不用 kyant 的 `drawBackdrop`：它默认带 `DefaultHighlight` + `DefaultShadow`，
 * 矩形四周会画一圈白色描边 —— 方形底栏上非常显眼（用户报告的「白线」）。
 */
@Composable
fun StandardBottomNav(
    selectedIndex: () -> Int,
    onTabSelected: (Int) -> Unit,
    items: List<GlassNavItem>,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    val surface = MiuixTheme.colorScheme.surface
    // 安全模式：液态玻璃那块曾经把绘制搞崩过，这里也不要再挂采样
    val blurActive = backdrop != null && !UiConfigState.safeMode
    // 有模糊时底栏本身透明：底色全部由 textureBlur 的 blendColors 给
    val barColor = if (blurActive) Color.Transparent else surface
    val colors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(color = surface.copy(alpha = 0.80f))),
    )
    val selected = selectedIndex()

    NavigationBar(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (blurActive) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        // 与 miuix-example 同值；强度倍率跟界面 ›「玻璃效果」走
                        blurRadius = 25f * glassBlurScale,
                        colors = colors,
                    )
                } else {
                    Modifier
                },
            )
            .background(barColor),
        color = barColor,
        showDivider = true,
        // 自己吃导航条 inset：底色要一路铺到屏幕最底（与原生 NavigationBar 一致）
        defaultWindowInsetsPadding = true,
    ) {
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = selected == index,
                onClick = { onTabSelected(index) },
                icon = item.icon,
                label = item.label,
            )
        }
    }
}

/** 标准底栏的总高度（不含系统导航条 inset） */
val StandardBottomNavHeight = 64.dp
