package com.xkspeed.HyperMount.ui.component.glass

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.xkspeed.HyperMount.ui.UiConfigState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import com.xkspeed.HyperMount.ui.util.stableNavigationBarsPadding

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

    // 底栏自己吃导航条 inset：底色与模糊要一路铺到屏幕最底（与原生 NavigationBar 一致）
    // 这里换成「忽略可见性」的高度并自己补：Miuix 内置用的是可见性感知的 navigationBars，
    // 切多任务时手势条一隐 整条会往下掉一段；关掉它、由外层统一画底并垫稳定高度
    Box(
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
    ) {
        Column(Modifier.stableNavigationBarsPadding()) {
            NavigationBar(
                modifier = Modifier.fillMaxWidth(),
                // 底色已经由外层铺满（含导航条那一段），这里不再叠一层
                color = Color.Transparent,
                showDivider = true,
                defaultWindowInsetsPadding = false,
            ) {
                items.forEachIndexed { index, item ->
                    val tune = NavIconTuning.items.getOrNull(index)
                        ?: NavIconTuning.defaultTune(index)
                    StandardNavItem(
                        selected = selected == index,
                        onClick = { onTabSelected(index) },
                        icon = item.icon,
                        label = item.label,
                        tune = tune,
                    )
                }
            }
        }
    }
}

/** 标准底栏的总高度（不含系统导航条 inset） */
val StandardBottomNavHeight = 64.dp

/**
 * 标准底栏的一格
 *
 * 布局与配色逐值复刻 Miuix 的 `NavigationBarItem`：高 64dp、图标上边距 8dp、
 * 文字 12sp、下边距 8dp，未选中 alpha 0.4、选中按下 0.5、未选中按下 0.6。
 *
 * 为什么不直接用 `NavigationBarItem`：它把图标写死成 `NavigationBarDefaults.IconSize`，
 * 而且 `icon` 参数只收 ImageVector，外面没有插手尺寸与位置的余地。
 * 自己搭一份之后，调试页那套 size / 位移 / 纵向缩放才能生效。
 */
@Composable
private fun RowScope.StandardNavItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    tune: NavIconTune,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 与 NavigationBarItemColors.contentColor 同一条规则（那个函数是 internal 调不到）
    val base = MiuixTheme.colorScheme.onSurfaceContainer
    val tint = when {
        pressed && selected ->
            base.copy(alpha = base.alpha * NavigationBarDefaults.SelectedPressedAlpha)

        pressed ->
            base.copy(alpha = base.alpha * NavigationBarDefaults.UnselectedPressedAlpha)

        selected -> base

        else ->
            base.copy(alpha = base.alpha * NavigationBarDefaults.UnselectedAlpha)
    }

    Column(
        modifier = Modifier
            .height(NavigationBarDefaults.ItemHeight)
            .weight(1f)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interaction,
                indication = null,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Image(
            imageVector = icon,
            // 相邻文字已经念出名字 图标不再重复读
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier
                .padding(top = NavigationBarDefaults.IconTopPadding)
                .navIconTune(tune),
        )
        Text(
            text = label,
            color = tint,
            textAlign = TextAlign.Center,
            fontSize = NavigationBarDefaults.LabelFontSize,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(bottom = NavigationBarDefaults.BottomPadding),
        )
    }
}
