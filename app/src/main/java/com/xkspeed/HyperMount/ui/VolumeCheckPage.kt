package com.xkspeed.HyperMount.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.xkspeed.HyperMount.core.Module
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.ui.components.BackIcon
import com.xkspeed.HyperMount.ui.components.SectionCard
import com.xkspeed.HyperMount.ui.components.StatusDot
import com.xkspeed.HyperMount.ui.util.pageContentPadding
import com.xkspeed.HyperMount.ui.util.pageScrollModifiers
import com.xkspeed.HyperMount.ui.util.rememberBlurBackdrop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 校验步骤状态 */
private sealed interface StepState {
    data object Idle : StepState
    data object Waiting : StepState
    data object Passed : StepState
    data object Failed : StepState
}

/**
 * 音量键校验
 *
 * 对应 check.sh 的 up / down / save：
 * 每步都会阻塞到用户按键或 10 秒超时 所以放在 IO 线程跑
 * 界面先显示"请按音量+"再等结果
 */
@Composable
fun VolumeCheckPage(
    onBack: () -> Unit,
    onFinished: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()

    var running by remember { mutableStateOf(false) }
    var upState by remember { mutableStateOf<StepState>(StepState.Idle) }
    var downState by remember { mutableStateOf<StepState>(StepState.Idle) }
    var hint by remember { mutableStateOf("按顺序按一次音量 + 和音量 − 用来确认按键事件能读到") }
    var saved by remember { mutableStateOf(false) }

    val backdrop = rememberBlurBackdrop()

    fun startCheck() {
        if (running) return
        running = true
        saved = false
        upState = StepState.Idle
        downState = StepState.Idle
        scope.launch {
            hint = "请按一次【音量 +】"
            upState = StepState.Waiting
            val upOut = Module.check("up").trim()
            val upLine = upOut.lineSequence().firstOrNull { it.startsWith("OK") }
            val upOk = upLine != null
            upState = if (upOk) StepState.Passed else StepState.Failed

            hint = "请按一次【音量 −】"
            downState = StepState.Waiting
            val downOut = Module.check("down").trim()
            val downLine = downOut.lineSequence().firstOrNull { it.startsWith("OK") }
            val downOk = downLine != null
            downState = if (downOk) StepState.Passed else StepState.Failed

            if (upOk && downOk) {
                // 把实测到的设备一起写进配置 救砖时不用再猜
                Module.saveVolumeCheck(
                    upLine.orEmpty().removePrefix("OK").trim(),
                    downLine.orEmpty().removePrefix("OK").trim(),
                )
                // 设置页里补校验也算数 记下这次的系统版本
                UiConfigState.markVolumeChecked(context)
                saved = true
                hint = "校验通过 已写入配置"
                onFinished()
            } else {
                hint = "有按键没读到 重试一次；实在不行检查音量键是否被其他模块占用"
            }
            running = false
        }
    }

    ScrollPage(
        title = "音量键校验",
        onBack = onBack,
        bottomPadding = WindowInsets.navigationBars.asPaddingValues()
            .calculateBottomPadding() + 24.dp,
    ) {
                item {
                    Text(
                        hint,
                        color = if (saved) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.subtitle,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                item { SmallTitle("校验步骤") }
                item {
                    SectionCard {
                        StepRow("音量 +", upState)
                        StepRow("音量 −", downState)
                    }
                }

                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Button(
                            onClick = { startCheck() },
                            enabled = !running,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (running) "等待按键…" else "开始校验")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "校验只是确认能读到按键事件 不改动系统设置 失败也可以继续用 只是救砖时按音量键可能没反应",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.subtitle,
                        )
                    }
                }
    }
}

@Composable
private fun StepRow(label: String, state: StepState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(
            when (state) {
                is StepState.Passed -> MiuixTheme.colorScheme.primary
                is StepState.Failed -> MiuixTheme.colorScheme.error
                StepState.Waiting -> MiuixTheme.colorScheme.primary.copy(alpha = 0.4f)
                StepState.Idle -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            }
        )
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MiuixTheme.textStyles.main,
                fontWeight = FontWeight.Medium,
            )
            Text(
                when (state) {
                    is StepState.Passed -> "已识别"
                    is StepState.Failed -> "未检测到按键"
                    StepState.Waiting -> "等待按键…"
                    StepState.Idle -> "未校验"
                },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
            )
        }
    }
}
