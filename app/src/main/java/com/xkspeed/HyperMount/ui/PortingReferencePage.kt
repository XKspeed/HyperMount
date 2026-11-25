package com.xkspeed.HyperMount.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.i18n.t
import com.xkspeed.HyperMount.ui.components.SectionCard
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 移植参考（二级界面）
 *
 * 只列「参考了谁」，不算第三方依赖：玻璃组件的原始实现是 AndroidLiquidGlass（Apache-2.0），
 * 经 miuix 示例、NexioSchedule、Sonify 一路改名搬运；我们按 1.0.5 原文重写主线，
 * 中间层只作结构参考。依赖本身的许可证在「第三方许可证」里单列。
 *
 * HyperCeiler 是唯一一处原文搬运：引导页的 assets/glow.glsl（AGSL 光晕着色器）
 * 与 GlowPainter 的 uniform 参数都来自它，AGPL-3.0，与本项目同协议。
 */
@Composable
fun PortingReferencePage(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    ScrollPage(
        title = t("移植参考", "Porting notes"),
        onBack = onBack,
        bottomPadding = 16.dp,
    ) {
        item { SmallTitle(t("参考项目", "Referenced projects")) }
        item {
            SectionCard {
                ArrowPreference(
                    title = "AndroidLiquidGlass",
                    summary = t("液态玻璃原始实现 · Apache-2.0", "Original liquid-glass implementation · Apache-2.0"),
                    onClick = {
                        runCatching { uriHandler.openUri("https://github.com/Kyant0/AndroidLiquidGlass") }
                    },
                )
                ArrowPreference(
                    title = "NexioSchedule",
                    summary = t("玻璃菜单参考 · AGPL-3.0", "Glass menu reference · AGPL-3.0"),
                    onClick = {
                        runCatching { uriHandler.openUri("https://github.com/HaoZai000/NexioSchedule") }
                    },
                )
                ArrowPreference(
                    title = "Sonify",
                    summary = t("玻璃组件结构参考", "Glass component structure reference"),
                    onClick = {
                        runCatching { uriHandler.openUri("https://github.com/XiaoZhi0729/Sonify") }
                    },
                )
                ArrowPreference(
                    title = "HyperCeiler",
                    summary = t("引导页光晕着色器原文 · AGPL-3.0", "Onboarding glow shader, verbatim · AGPL-3.0"),
                    onClick = {
                        runCatching { uriHandler.openUri("https://github.com/ReChronoRain/HyperCeiler") }
                    },
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
