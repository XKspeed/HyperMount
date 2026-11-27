package com.xkspeed.HyperMount.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.xkspeed.HyperMount.i18n.t
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.ui.component.oobe.OobeTuning
import com.xkspeed.HyperMount.ui.components.SectionCard
import com.xkspeed.HyperMount.ui.util.stableNavigationBarHeight
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.SmallTitle

/**
 * 调试页：只有非正式签名（debug 包 / MT 重签的测试包）才进得来。
 *
 * OOBE 的光晕与混色已经定稿，滑条和调色板都撤了，数值在
 * [OobeTuning.Def] 里。这里留两样：
 * - 进入 OOBE：不用清数据、不用等下次冷启动，直接拿定稿参数放一遍首屏
 * - 导出调试配置：把当前参数吐成 JSON 复制走，下次再调参不用从零开始
 */
@Composable
fun DebugPage(onBack: () -> Unit, onOpenOobe: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

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
                BasicComponent(
                    title = t("导出调试配置", "Export debug config"),
                    onClick = {
                        clipboard.setText(AnnotatedString(OobeTuning.toJson()))
                        Toast.makeText(
                            context,
                            t("已复制到剪贴板", "Copied to clipboard"),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
        }
    }
}
