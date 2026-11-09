package com.xkspeed.HyperMount.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.roundToInt

/**
 * 让滑条支持点击节点跳转
 *
 * Miuix 的 Slider 只认拖动：draggable 要过 touch slop 才开始动，单击轨道没有反应。
 * 这里在轨道上叠一层点击 按落点折算成档位下标直接跳过去 —— 档位数与 Slider 的
 * `steps = 档位数 - 2` 同口径。滑轨两端各让出半个圆钮 和 Slider 内部算法一致。
 */
@Composable
fun Modifier.sliderTapSeek(steps: Int, onSeek: (Int) -> Unit): Modifier {
    var width by remember { mutableIntStateOf(0) }
    val seek by rememberUpdatedState(onSeek)
    return this
        .onSizeChanged { width = it.width }
        .pointerInput(steps, width) {
            detectTapGestures { offset ->
                val radius = size.height / 2f
                val usable = (width - 2f * radius).coerceAtLeast(1f)
                val fraction = ((offset.x - radius) / usable).coerceIn(0f, 1f)
                seek((fraction * steps).roundToInt())
            }
        }
}

/**
 * 连续滑条的点击跳值：返回 0f..1f 的落点比例，由调用方换算成自己的值域。
 *
 * 和 [sliderTapSeek] 同一套让位算法，只是不取整。
 */
@Composable
fun Modifier.sliderTapFraction(onSeek: (Float) -> Unit): Modifier {
    var width by remember { mutableIntStateOf(0) }
    val seek by rememberUpdatedState(onSeek)
    return this
        .onSizeChanged { width = it.width }
        .pointerInput(width) {
            detectTapGestures { offset ->
                val radius = size.height / 2f
                val usable = (width - 2f * radius).coerceAtLeast(1f)
                seek(((offset.x - radius) / usable).coerceIn(0f, 1f))
            }
        }
}
