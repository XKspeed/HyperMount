package com.xkspeed.HyperMount.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.xkspeed.HyperMount.i18n.t
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.ui.components.SectionCard
import com.xkspeed.HyperMount.ui.util.stableNavigationBarHeight
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.SmallTitle

/**
 * 调试页：只有非正式签名（debug 包 / MT 重签的测试包）才进得来。
 *
 * OOBE 的光晕与混色已经定稿，滑条和调色板都撤了，
 * 数值在 [com.xkspeed.HyperMount.ui.component.oobe.OobeTuning.Def]。
 * 这里只剩一个「进入 OOBE」：不用清数据、不用等下次冷启动，直接拿定稿参数放一遍首屏。
 */
@Composable
fun DebugPage(onBack: () -> Unit, onOpenOobe: () -> Unit) {
    ScrollPage(
        title = t("调试", "Debug"),
        onBack = onBack,
        bottomPadding = stableNavigationBarHeight() + 24.dp,
    ) {
        item { SmallTitle(t("OOBE", "OOBE")) }
        item {
            SectionCard {
                BasicComponent(
                    title = t("进入 OOBE", "Open OOBE"),
                    summary = t("放一遍首屏，看光晕和混色", "Preview the first screen"),
                    onClick = onOpenOobe,
                )
            }
        }
    }
}
