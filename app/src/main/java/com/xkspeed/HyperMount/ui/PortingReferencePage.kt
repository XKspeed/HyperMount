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
 * 只列「参考了谁」，不算第三方依赖：玻璃组件与图标的写法来自 Sonify，
 * 依赖本身的许可证在「第三方许可证」里单列。
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
                    title = "Sonify",
                    summary = "玻璃组件及图标参考",
                    onClick = {
                        runCatching { uriHandler.openUri("https://github.com/XiaoZhi0729/Sonify") }
                    },
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
