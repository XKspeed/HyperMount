package com.xkspeed.HyperMount.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xkspeed.HyperMount.ui.component.OverScrollTuning
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.ui.components.SectionCard
import com.xkspeed.HyperMount.ui.components.SliderInputPreference
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.SmallTitle
import kotlin.math.roundToInt

/**
 * 调试页：只有非正式签名（debug 包 / MT 重签的测试包）才进得来。
 *
 * 目前只放过滚动这一项，后续再加别的实验开关都放这里，
 * 不再往正式设置页里塞。
 */
@Composable
fun DebugPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val fraction = OverScrollTuning.maxFraction
    val maxDp = (screenHeightDp * fraction).roundToInt()

    ScrollPage(
        title = "调试",
        onBack = onBack,
        bottomPadding = WindowInsets.navigationBars.asPaddingValues()
            .calculateBottomPadding() + 24.dp,
    ) {
        item { SmallTitle("过滚动") }
        item {
            SectionCard {
                SliderInputPreference(
                    title = "最大拉伸距离",
                    summary = "最多能拉出 $maxDp dp（屏高 $screenHeightDp dp 的 " +
                        "${(fraction * 100).roundToInt()}%）",
                    value = fraction,
                    onValueChange = { OverScrollTuning.set(it) },
                    onValueChangeFinished = { OverScrollTuning.save(context) },
                    valueRange = OverScrollTuning.MIN_MAX_FRACTION..OverScrollTuning.MAX_MAX_FRACTION,
                    // 0.01 一档：0.10 → 1.00 共 90 个位置
                    steps = 89,
                    valueText = "${(fraction * 100).roundToInt()}%",
                    inputRange = 10f..100f,
                    toInput = { it * 100f },
                    fromInput = { it / 100f },
                    inputUnit = "%",
                )
                BasicComponent(
                    title = "恢复默认",
                    summary = "回到 33%（与改动前的手感一致）",
                    onClick = {
                        OverScrollTuning.reset()
                        OverScrollTuning.save(context)
                    },
                )
            }
        }
        item { SmallTitle("说明") }
        item {
            SectionCard {
                BasicComponent(
                    title = "拉伸曲线",
                    summary = "越拉越紧 拉到上限后不再位移",
                )
                BasicComponent(
                    title = "生效范围",
                    summary = "页面滚动与主页 / 界面页的日志区共用",
                )
            }
        }
    }
}
