package com.xkspeed.HyperMount.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.ui.components.SectionCard
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 移植参考（二级界面）
 *
 * 只列「参考了谁」，不算第三方依赖：玻璃组件的原始实现是 AndroidLiquidGlass（Apache-2.0），
 * 经 miuix 示例、NexioSchedule、Sonify 一路改名搬运；我们按 1.0.5 原文重写主线，
 * 中间层只作结构参考。依赖本身的许可证在「第三方许可证」里单列。
 */
@Composable
fun PortingReferencePage(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    ScrollPage(
        title = "移植参考",
        onBack = onBack,
        bottomPadding = 16.dp,
    ) {
        item { SmallTitle("参考项目") }
        item {
            SectionCard {
                ArrowPreference(
                    title = "AndroidLiquidGlass",
                    summary = "液态玻璃原始实现 · Apache-2.0",
                    onClick = {
                        runCatching { uriHandler.openUri("https://github.com/Kyant0/AndroidLiquidGlass") }
                    },
                )
                ArrowPreference(
                    title = "NexioSchedule",
                    summary = "玻璃菜单参考 · AGPL-3.0",
                    onClick = {
                        runCatching { uriHandler.openUri("https://github.com/HaoZai000/NexioSchedule") }
                    },
                )
                ArrowPreference(
                    title = "Sonify",
                    summary = "玻璃组件结构参考",
                    onClick = {
                        runCatching { uriHandler.openUri("https://github.com/XiaoZhi0729/Sonify") }
                    },
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
