package com.xkspeed.HyperMount.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xkspeed.HyperMount.core.Module
import com.xkspeed.HyperMount.core.ModuleState
import com.xkspeed.HyperMount.core.SystemVersion
import com.xkspeed.HyperMount.core.UpdateInfo
import kotlinx.coroutines.delay
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.ui.components.InfoRow
import com.xkspeed.HyperMount.ui.components.SectionCard
import com.xkspeed.HyperMount.ui.components.StatusDot
import com.xkspeed.HyperMount.ui.util.LocalIsWideScreen
import com.xkspeed.HyperMount.ui.util.pageContentPadding
import com.xkspeed.HyperMount.ui.util.pageScrollModifiers
import com.xkspeed.HyperMount.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog
import com.xkspeed.HyperMount.ui.component.mountOverScrollVertical

/** 有新版本时的绿色胶囊 与主页那颗同色 */
private val UpdateGreen = Color(0xFF2E7D32)

@Composable
fun HomePage(
    padding: PaddingValues,
    state: ModuleState,
    rootOk: Boolean?,
    busy: Boolean,
    moduleLatest: UpdateInfo?,
    onOpenAbout: () -> Unit,
    onPickRescue: () -> Unit,
    onPickTarget: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val isWideScreen = LocalIsWideScreen.current
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()
    var showStartConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val backdrop = rememberBlurBackdrop()

    val canStart = rootOk == true && state.rescueReady && state.targetOk &&
        !state.daemonRunning && !busy

    // 和主页同一条标准：模块默认不带日志目录 没选就不让开始
    val logDirReady = state.logDir.isNotBlank()

    // 守护运行时把状态卡撑开 直接在这里看脚本输出 停下来再收回
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val logListState = rememberLazyListState()
    // 放单例：切到设置再回来 这一轮的输出还在
    val logLines = HomeUiState.logLines
    val hasRun = HomeUiState.hasRun

    LaunchedEffect(state.daemonRunning) {
        if (state.daemonRunning) {
            // 新的一轮：先把上一轮留下的输出清掉，避免开场闪一下旧日志
            if (hasRun) HomeUiState.logLines = emptyList()
            HomeUiState.hasRun = true
            while (true) {
                val text = Module.tailLog(300)
                HomeUiState.logLines = if (text.isBlank()) emptyList() else text.lines()
                delay(1500)
            }
        } else if (hasRun) {
            // 流程结束（走完页面 / 音量键）时再补读一次：
            // 轮询协程刚被取消，最后几行不能因此丢掉
            val text = Module.tailLog(300)
            if (text.isNotBlank()) HomeUiState.logLines = text.lines()
        }
    }

    // 只在贴底时跟随 往上翻历史不被拽回来
    LaunchedEffect(logLines.size) {
        if (logLines.isEmpty()) return@LaunchedEffect
        val last = logListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (last >= logLines.size - 3) {
            runCatching { logListState.scrollToItem(logLines.size - 1) }
        }
    }

    val logAreaHeight by animateDpAsState(
        // 跑完不收起：把上一次的输出留在原地，直到下次开始或退出页面
        targetValue = if (state.daemonRunning || logLines.isNotEmpty()) {
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
        // 和主页同款：版本号右边冒一颗绿色胶囊 只有清单里的模块版本比本地新才出现
        subtitleExtra = {
            AnimatedVisibility(
                visible = moduleLatest != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Row(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(UpdateGreen)
                        .clickable(onClick = onOpenAbout)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Update,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        "模块有新版本",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
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
                                        // 和主页一致：日志区自己吃掉滚动 到边只回弹不带走页面
                                        LazyColumn(
                                            state = logListState,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .scrollEndHaptic()
                                                .mountOverScrollVertical(nestedScrollToParent = false),
                                        ) {
                                            itemsIndexed(logLines) { _, line ->
                                                Text(
                                                    line.ifEmpty { " " },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    color = when {
                                                        isErrorLine(line) ->
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
                            // 跑起来之后两个 apk 都锁死：脚本用的是启动那一刻的路径
                            enabled = !state.daemonRunning && !busy,
                        )
                        ArrowPreference(
                            title = "待测界面",
                            summary = when {
                                !state.targetOk -> "未选择 · 点击选择要测试的 APK"
                                else -> state.targetPath.substringAfterLast('/') +
                                    " · " + Module.fmtSize(state.targetSize)
                            },
                            onClick = onPickTarget,
                            enabled = !state.daemonRunning && !busy,
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
                                    onClick = {
                                        // 只判「有没有配」：目录不存在由 action.sh 的 mkdir -p 建
                                        if (state.logDir.isBlank()) {
                                            toast(context, "未指定日志目录")
                                            return@Button
                                        }
                                        showStartConfirm = true
                                    },
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
                                    !logDirReady -> "未指定日志目录 去设置里选一个"
                                    state.daemonRunning -> "守护已启动 音量+ 触发救砖 音量- 取消"
                                    else -> "确认后立即替换界面 随后监听崩溃与音量键"
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

    // 开始前二次确认：确认完立刻把待测界面换进模块目录 不再等
    if (showStartConfirm) {
        WindowDialog(
            show = true,
            title = "开始测试",
            summary = "确认后立即替换界面",
            maxWidth = 560.dp,
            onDismissRequest = { showStartConfirm = false },
        ) {
            Text(
                state.targetPath.substringAfterLast('/'),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
            )
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "取消",
                    onClick = { showStartConfirm = false },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "继续",
                    onClick = {
                        // 确认这会儿配置也可能被清空：开跑前再核一次
                        if (state.logDir.isBlank()) {
                            toast(context, "未指定日志目录")
                            return@TextButton
                        }
                        showStartConfirm = false
                        onStart()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** 界面页这一轮的状态：切页回来还在 */
private object HomeUiState {
    var logLines by mutableStateOf<List<String>>(emptyList())
    var hasRun by mutableStateOf(false)
}

private fun toast(context: Context, msg: String) {
    if (msg.isNotBlank()) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
