package com.xkspeed.HyperMount.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xkspeed.HyperMount.ui.component.glass.NavIconTuning
import com.xkspeed.HyperMount.i18n.t
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.ui.components.SectionCard
import com.xkspeed.HyperMount.ui.components.SliderInputPreference
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.xkspeed.HyperMount.ui.util.stableNavigationBarHeight

/**
 * 调试页：只有非正式签名（debug 包 / MT 重签的测试包）才进得来。
 *
 * 放的都是拿不准、需要真机看着调的参数；定下来之后入口就撤掉，
 * 值写死进代码（过滚动那块就是这么收尾的）。
 */
@Composable
fun DebugPage(onBack: () -> Unit, onOpenOnboarding: () -> Unit) {
    ScrollPage(
        title = t("调试", "Debug"),
        onBack = onBack,
        bottomPadding = stableNavigationBarHeight() + 24.dp,
    ) {
        item { SmallTitle(t("引导", "Onboarding")) }
        item {
            SectionCard {
                BasicComponent(
                    title = t("重新查看引导", "Replay onboarding"),
                    summary = t("功能说明 · 环境检查 · 音量键校验", "Intro · environment check · volume key check"),
                    onClick = onOpenOnboarding,
                )
            }
        }

        // ---- 底栏图标 ----
        item { SmallTitle(t("底栏图标", "Bottom bar icons")) }
        item {
            SectionCard {
                BasicComponent(
                    title = t("三项一起恢复默认", "Reset all three"),
                    summary = t("只重置底栏 不动顶栏", "Resets the bottom bar only, not the top bar"),
                    onClick = { NavIconTuning.resetAll() },
                )
            }
        }
        NavIconTuning.labels.forEachIndexed { index, label ->
            item { SmallTitle(label) }
            item {
                // 在这一层读值：元素被替换才会重组到对应滑条
                val tune = NavIconTuning.items[index]
                SectionCard {
                    SliderInputPreference(
                        title = t("图标大小", "Icon size"),
                        summary = t("底栏这一项的图标边长", "Icon edge length for this tab"),
                        value = tune.size,
                        defaultValue = NavIconTuning.defaultTune(index).size,
                        onValueChange = { NavIconTuning.setSize(index, it) },
                        // 无极拖动 松手吸附到 1dp 档
                        onValueChangeFinished = { NavIconTuning.snapSize(index) },
                        valueRange = NavIconTuning.MIN_SIZE..NavIconTuning.MAX_SIZE,
                        steps = 0,
                        valueText = "${formatDecimal(tune.size)} dp",
                        inputUnit = "dp",
                    )
                    SliderInputPreference(
                        title = t("水平偏移", "Horizontal offset"),
                        summary = t("正数向右 负数向左", "Positive moves right, negative left"),
                        value = tune.offsetX,
                        defaultValue = NavIconTuning.defaultTune(index).offsetX,
                        onValueChange = { NavIconTuning.setOffsetX(index, it) },
                        onValueChangeFinished = { NavIconTuning.snapOffsetX(index) },
                        valueRange = NavIconTuning.MIN_OFFSET..NavIconTuning.MAX_OFFSET,
                        steps = 0,
                        valueText = "${formatOffset(tune.offsetX)} dp",
                        inputUnit = "dp",
                    )
                    SliderInputPreference(
                        title = t("垂直偏移", "Vertical offset"),
                        summary = t("正数向下 负数向上", "Positive moves down, negative up"),
                        value = tune.offsetY,
                        defaultValue = NavIconTuning.defaultTune(index).offsetY,
                        onValueChange = { NavIconTuning.setOffsetY(index, it) },
                        onValueChangeFinished = { NavIconTuning.snapOffsetY(index) },
                        valueRange = NavIconTuning.MIN_OFFSET..NavIconTuning.MAX_OFFSET,
                        steps = 0,
                        valueText = "${formatOffset(tune.offsetY)} dp",
                        inputUnit = "dp",
                    )
                    SliderInputPreference(
                        title = t("上下压缩", "Vertical scale"),
                        summary = t("小于 1 压扁 大于 1 拉高 只改高度不改宽度", "Below 1 squashes, above 1 stretches — height only"),
                        value = tune.scaleY,
                        defaultValue = NavIconTuning.defaultTune(index).scaleY,
                        onValueChange = { NavIconTuning.setScaleY(index, it) },
                        // 无极拖动 松手吸附到 0.05 档
                        onValueChangeFinished = { NavIconTuning.snapScaleY(index) },
                        valueRange = NavIconTuning.MIN_SCALE_Y..NavIconTuning.MAX_SCALE_Y,
                        steps = 0,
                        valueText = "%.2f×".format(tune.scaleY),
                        // 输入框直接填倍数本身（1.15），不做百分比换算
                        inputUnit = "×",
                    )
                    BasicComponent(
                        title = t("恢复这一项", "Reset this one"),
                        summary = t("只重置 $label", "Resets $label only"),
                        onClick = { NavIconTuning.reset(index) },
                    )
                }
            }
        }

        // ---- 导出 ----
        item { SmallTitle(t("导出", "Export")) }
        item {
            SectionCard {
                val context = LocalContext.current
                Column(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = {
                                    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as ClipboardManager
                                    manager.setPrimaryClip(
                                        ClipData.newPlainText(
                                            "底栏图标参数",
                                            NavIconTuning.toJson(),
                                        ),
                                    )
                                    Toast.makeText(
                                        context,
                                        t("已复制底栏图标 JSON", "Bottom-bar icon JSON copied"),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = t("导出 JSON", "Export JSON"),
                        style = MiuixTheme.textStyles.subtitle,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = t("长按这一行复制当前三个图标的参数", "Long-press this row to copy all three icons' values"),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.subtitle,
                    )
                }
            }
        }
    }
}

/** 偏移量显示成带符号的数：0 / +1 / -1.5 */
private fun formatOffset(value: Float): String {
    val text = formatDecimal(value)
    return if (value > 0f) "+$text" else text
}

/** 拖动中显示一位小数 吸附到整数后不带小数点 */
private fun formatDecimal(value: Float): String =
    if (value == value.toInt().toFloat()) {
        value.toInt().toString()
    } else {
        "%.1f".format(value)
    }
