package com.xkspeed.HyperMount.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.ui.component.glass.TopBarTuning
import com.xkspeed.HyperMount.ui.components.SectionCard
import com.xkspeed.HyperMount.ui.components.SliderInputPreference
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.SmallTitle
import kotlin.math.roundToInt

/**
 * 调试页：只有非正式签名（debug 包 / MT 重签的测试包）才进得来。
 *
 * 放的都是拿不准、需要真机看着调的参数；定下来之后入口就撤掉，
 * 值写死进代码（过滚动那块就是这么收尾的）。
 */
@Composable
fun DebugPage(onBack: () -> Unit, onOpenOnboarding: () -> Unit) {
    val iconSize = TopBarTuning.actionIconSize
    val offsetX = TopBarTuning.actionOffsetX
    val offsetY = TopBarTuning.actionOffsetY

    ScrollPage(
        title = "调试",
        onBack = onBack,
        bottomPadding = WindowInsets.navigationBars.asPaddingValues()
            .calculateBottomPadding() + 24.dp,
    ) {
        item { SmallTitle("引导") }
        item {
            SectionCard {
                BasicComponent(
                    title = "重新查看引导",
                    summary = "功能说明 · 环境检查 · 音量键校验",
                    onClick = onOpenOnboarding,
                )
            }
        }
        item { SmallTitle("顶栏") }
        item {
            SectionCard {
                SliderInputPreference(
                    title = "主题键图标大小",
                    summary = "设置页右上角那个键 默认 26dp",
                    value = iconSize,
                    defaultValue = TopBarTuning.DEFAULT_ACTION_ICON_SIZE,
                    onValueChange = { TopBarTuning.setSize(it) },
                    valueRange = TopBarTuning.MIN_ACTION_ICON_SIZE..
                        TopBarTuning.MAX_ACTION_ICON_SIZE,
                    // 16～30dp 每 1dp 一档 共 13 个中间位置
                    steps = 13,
                    valueText = "${iconSize.roundToInt()} dp",
                    inputUnit = "dp",
                )
                SliderInputPreference(
                    title = "水平偏移",
                    summary = "正数向右 负数向左",
                    value = offsetX,
                    defaultValue = 0f,
                    onValueChange = { TopBarTuning.setOffsetX(it) },
                    valueRange = TopBarTuning.MIN_ACTION_OFFSET..
                        TopBarTuning.MAX_ACTION_OFFSET,
                    // ±6dp 每 0.5dp 一档 共 24 段
                    steps = 23,
                    valueText = "${formatOffset(offsetX)} dp",
                    inputUnit = "dp",
                )
                SliderInputPreference(
                    title = "垂直偏移",
                    summary = "正数向下 负数向上 头重脚轻就往下压",
                    value = offsetY,
                    defaultValue = TopBarTuning.DEFAULT_ACTION_OFFSET_Y,
                    onValueChange = { TopBarTuning.setOffsetY(it) },
                    valueRange = TopBarTuning.MIN_ACTION_OFFSET..
                        TopBarTuning.MAX_ACTION_OFFSET,
                    steps = 23,
                    valueText = "${formatOffset(offsetY)} dp",
                    inputUnit = "dp",
                )
                BasicComponent(
                    title = "恢复默认",
                    summary = "回到 26dp 下移 0.5dp",
                    onClick = { TopBarTuning.reset() },
                )
            }
        }
    }
}

/** 偏移量显示成带符号的数：0 / +1 / -1.5 */
private fun formatOffset(value: Float): String {
    val text = if (value % 1f == 0f) {
        value.toInt().toString()
    } else {
        "%.1f".format(value)
    }
    return if (value > 0f) "+$text" else text
}
