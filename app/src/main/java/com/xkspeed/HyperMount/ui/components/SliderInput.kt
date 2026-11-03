package com.xkspeed.HyperMount.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt

/**
 * 带数值输入的滑动条
 *
 * 和 SliderPreference 外观完全一致 区别是点标题那一片会弹一个对话框 能直接敲数字
 * 拖滑轨仍然是原来的行为（滑轨自己吃掉点按事件 不会误触发对话框）
 */
@Composable
fun SliderInputPreference(
    title: String,
    summary: String? = null,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    // 档位滑条只有几个固定点 手敲数值没意义 关掉后点标题不会弹输入框
    inputEnabled: Boolean = true,
    // 关键点：滑条上画出这几个刻度 拖到附近会被吸过去（阈值按轨道长度的比例算）
    showKeyPoints: Boolean = false,
    keyPoints: List<Float>? = null,
    magnetThreshold: Float = 0.02f,
    hapticEffect: SliderDefaults.SliderHapticEffect = SliderDefaults.DefaultHapticEffect,
    valueText: String,
    // 对话框里显示的数值区间 与内部值不同的（比如 0..2 显示成 0..200%）自己映射
    inputRange: ClosedFloatingPointRange<Float> = valueRange,
    toInput: (Float) -> Float = { it },
    fromInput: (Float) -> Float = { it },
    inputUnit: String = "",
    // 卡片里保持 16dp 的默认内缩；放进对话框时传 0 让滑轨和输入框一样宽
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
) {
    var showInput by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    // 拖动过程中 value 一直在变 用 rememberUpdatedState 免得每帧重启手势检测
    val currentValue by rememberUpdatedState(value)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(enabled, inputEnabled) {
                if (!enabled || !inputEnabled) return@pointerInput
                detectTapGestures(
                    onTap = {
                        val v = toInput(currentValue)
                        text = if (v == v.roundToInt().toFloat()) {
                            v.roundToInt().toString()
                        } else {
                            v.toString()
                        }
                        showInput = true
                    },
                )
            },
    ) {
        SliderPreference(
            title = title,
            summary = summary,
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            valueText = valueText,
            insideMargin = insideMargin,
            showKeyPoints = showKeyPoints,
            keyPoints = keyPoints,
            magnetThreshold = magnetThreshold,
            hapticEffect = hapticEffect,
        )
    }

    WindowDialog(
        show = showInput,
        title = title,
        summary = "输入 " + inputRange.start.roundToInt() + " 到 " +
            inputRange.endInclusive.roundToInt() + " 之间的数值" +
            if (inputUnit.isBlank()) "" else "（$inputUnit）",
        maxWidth = 560.dp,
        onDismissRequest = { showInput = false },
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            label = "数值",
            useLabelAsPlaceholder = true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = "取消",
                onClick = { showInput = false },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = "确定",
                onClick = {
                    val typed = text.trim().toFloatOrNull()
                    if (typed == null) {
                        // 输入框里不是数字 就别改值 让用户重新点一次
                        showInput = false
                        return@TextButton
                    }
                    val clamped = typed.coerceIn(inputRange.start, inputRange.endInclusive)
                    onValueChange(fromInput(clamped))
                    onValueChangeFinished()
                    showInput = false
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 弹窗里的说明文字 统一小一号 */
@Composable
fun DialogHint(text: String) {
    Text(
        text = text,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.subtitle,
    )
}
