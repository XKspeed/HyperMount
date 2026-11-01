package com.xkspeed.systemuimount.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xkspeed.systemuimount.core.Module
import com.xkspeed.systemuimount.core.ModuleState
import com.xkspeed.systemuimount.core.SystemVersion
import kotlinx.coroutines.delay
import com.xkspeed.systemuimount.ui.component.glass.ScrollPage
import com.xkspeed.systemuimount.ui.components.InfoRow
import com.xkspeed.systemuimount.ui.components.SectionCard
import com.xkspeed.systemuimount.ui.components.StatusDot
import com.xkspeed.systemuimount.ui.util.LocalIsWideScreen
import com.xkspeed.systemuimount.ui.util.pageContentPadding
import com.xkspeed.systemuimount.ui.util.pageScrollModifiers
import com.xkspeed.systemuimount.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HomePage(
    padding: PaddingValues,
    state: ModuleState,
    rootOk: Boolean?,
    busy: Boolean,
    onPickRescue: () -> Unit,
    onPickTarget: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val isWideScreen = LocalIsWideScreen.current
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()

    val backdrop = rememberBlurBackdrop()

    val canStart = rootOk == true && state.rescueReady && state.targetOk &&
        !state.daemonRunning && !busy

    // 守护运行时把状态卡撑开 直接在这里看脚本输出 停下来再收回
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val logListState = rememberLazyListState()
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(state.daemonRunning) {
        if (!state.daemonRunning) return@LaunchedEffect
        while (true) {
            val text = Module.tailLog(300)
            logLines = if (text.isBlank()) emptyList() else text.lines()
            delay(1500)
        }
    }

    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            runCatching { logListState.scrollToItem(logLines.size - 1) }
        }
    }

    val logAreaHeight by animateDpAsState(
        targetValue = if (state.daemonRunning) {
            (screenHeight * 0.3f - 96.dp).coerceAtLeast(120.dp)
        } else {
            0.dp
        },
        animationSpec = tween(380, easing = FastOutSlowInEasing),
        label = "logArea",
    )

    ScrollPage(
        title = "界面",
        subtitle = if (state.version.isNotBlank()) "救砖与界面替换 · 模块 v${state.version}" else "救砖与界面替换",
        // 底栏高度由外层 Scaffold 给出 再加一点呼吸
        bottomPadding = padding.calculateBottomPadding() + 16.dp,
    ) {
                // ---- 状态卡 ----
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(
                                    if (state.daemonRunning) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                                Text(
                                    if (state.daemonRunning) "守护中" else "空闲",
                                    style = MiuixTheme.textStyles.title2,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                buildString {
                                    if (state.daemonRunning) append("PID ${state.running}")
                                    else append("未在运行")
                                    append(" · 已救砖 ${state.protectCount} 次")
                                    if (state.rescueOff) append(" · 系统救援已屏蔽")
                                },
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.subtitle,
                            )
                            if (logAreaHeight > 0.dp) {
                                Spacer(Modifier.height(12.dp))
                                Box(
                                    Modifier.fillMaxWidth()
                                        .height(logAreaHeight)
                                        .clipToBounds()
                                ) {
                                    if (logLines.isEmpty()) {
                                        Text(
                                            "等待脚本输出…",
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            style = MiuixTheme.textStyles.subtitle,
                                        )
                                    } else {
                                        LazyColumn(state = logListState) {
                                            itemsIndexed(logLines) { _, line ->
                                                Text(
                                                    line.ifEmpty { " " },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    color = when {
                                                        line.contains("FAIL") || line.contains("错误") ->
                                                            MiuixTheme.colorScheme.error
                                                        line.contains("OK") || line.contains("成功") ->
                                                            MiuixTheme.colorScheme.primary
                                                        else -> MiuixTheme.colorScheme.onSurface
                                                    },
                                                    fontSize = 10.sp,
                                                    lineHeight = 14.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (rootOk == false) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    "未获得 root 权限",
                                    color = MiuixTheme.colorScheme.error,
                                    style = MiuixTheme.textStyles.main,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "请在弹出的授权框里允许本应用使用 su 然后重新打开",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.subtitle,
                                )
                            }
                        }
                    }
                }

                // ---- 文件 ----
                item { SmallTitle("文件") }
                item {
                    SectionCard {
                        ArrowPreference(
                            title = "救砖界面",
                            summary = if (state.rescueReady)
                                "MiuiSystemUI.apk · ${Module.fmtSize(state.rescueSize)}"
                            else "未选择 · 点击选择要恢复的 APK",
                            onClick = onPickRescue,
                        )
                        ArrowPreference(
                            title = "待测界面",
                            summary = when {
                                !state.targetOk -> "未选择 · 点击选择要测试的 APK"
                                else -> state.targetPath.substringAfterLast('/') +
                                    " · " + Module.fmtSize(state.targetSize)
                            },
                            onClick = onPickTarget,
                        )
                    }
                }

                // ---- 操作 ----
                item { SmallTitle("操作") }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = onStart,
                                    enabled = canStart,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(if (busy) "启动中…" else "开始测试")
                                }
                                Button(
                                    onClick = onStop,
                                    enabled = state.daemonRunning && !busy,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("停止")
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                when {
                                    rootOk != true -> "等待 root 授权"
                                    !state.rescueReady -> "先选一个救砖界面（能正常开机的 SystemUI）"
                                    !state.targetOk -> "再选一个待测界面"
                                    state.daemonRunning -> "守护已启动 音量+ 触发救砖 音量- 取消"
                                    else -> "启动后 5 秒自动替换界面 随后监听崩溃与音量键"
                                },
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.subtitle,
                            )
                        }
                    }
                }

                // ---- 设备 ----
                val sysBuild = SystemVersion.build
                if (state.model.isNotBlank() || sysBuild.isNotBlank() || state.androidVer.isNotBlank()) {
                    item { SmallTitle("设备") }
                    item {
                        SectionCard {
                            // 只给 ROM 的具体版本串（4.0.24.0.WOCCNXM 这种）
                            // os4.0 / Android 16 这类大版本号不参与展示
                            val sysVer = sysBuild.ifBlank { state.androidVer }
                            if (sysVer.isNotBlank()) InfoRow("系统", sysVer)
                            if (state.model.isNotBlank()) InfoRow("机型", state.model)
                            InfoRow(
                                "音量键",
                                if (state.volumeAssessed) "已识别" else "未校验",
                            )
                        }
                    }
    }
}
}
