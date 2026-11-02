package com.xkspeed.HyperMount.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xkspeed.HyperMount.core.AppVersion
import com.xkspeed.HyperMount.core.LogConfig
import com.xkspeed.HyperMount.core.Logcat
import com.xkspeed.HyperMount.core.Module
import com.xkspeed.HyperMount.core.ModuleState
import com.xkspeed.HyperMount.core.UpdateInfo
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.ui.components.SectionCard
import com.xkspeed.HyperMount.ui.components.SliderInputPreference
import com.xkspeed.HyperMount.ui.components.StatusDot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt
import com.xkspeed.HyperMount.ui.component.mountOverScrollVertical

/** 有新版本时的绿色胶囊 */
private val UpdateGreen = Color(0xFF2E7D32)

/**
 * 主页：自己的测试流程
 *
 * 逻辑照搬「双兼容版 logcat.sh」：
 * 后台 pm install 待测 apk → 等用户设定的时长 → 抓 crash 日志
 * 自动替换打开时再盯日志大小 超阈值就把救砖 apk 装回去
 *
 * 和「界面」页完全独立：apk 选完落在 App 私有目录 开跑时才拷到 /data/local/tmp
 * 也不依赖模块守护 自动替换关掉也能单独抓日志
 */
@Composable
fun MainPage(
    padding: PaddingValues,
    state: ModuleState,
    rootOk: Boolean?,
    latest: UpdateInfo?,
    onOpenRelease: () -> Unit,
    onPickRescue: () -> Unit,
    onPickTarget: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 自动替换是主页自己的开关 存在本地 不读也不改模块的 LOG_MODE
    val autoReplace = LogConfig.autoReplace

    var showConfig by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<Logcat.Session?>(null) }
    var running by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var logSize by remember { mutableStateOf(0L) }
    val logListState = rememberLazyListState()

    // 测试在跑 或者脚本刚起来还没读到状态 就把日志区撑开
    val showLog = running || session != null

    // 跑着就每 1.5 秒拉一次运行日志与状态
    LaunchedEffect(session?.file) {
        val s = session ?: return@LaunchedEffect
        while (true) {
            val p = Logcat.poll(s.file)
            logLines = if (p.text.isBlank()) emptyList() else p.text.lines()
            logSize = p.size
            running = p.running
            if (!p.running) break
            delay(1500)
        }
    }

    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            runCatching { logListState.scrollToItem(logLines.size - 1) }
        }
    }

    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val logAreaHeight by animateDpAsState(
        targetValue = if (showLog) {
            (screenHeight * 0.3f - 96.dp).coerceAtLeast(120.dp)
        } else {
            0.dp
        },
        animationSpec = tween(380, easing = FastOutSlowInEasing),
        label = "mainLogArea",
    )

    // 两份 apk 都是主页自己选的 和「界面」页各存各的
    val rescueReady = LogConfig.rescuePath.isNotBlank()
    val targetReady = LogConfig.targetPath.isNotBlank()

    // 自动替换关掉时只抓日志 所以不要求选救砖 apk
    val canStart = rootOk == true && targetReady && !running && !starting &&
        (!autoReplace || rescueReady)

    val rescueCount = logLines.count { it.contains("救砖成功") }

    fun startTest() {
        if (!targetReady) {
            toast(context, "先选待测apk")
            return
        }
        if (autoReplace && !rescueReady) {
            toast(context, "先选救砖apk 或者把自动替换关掉")
            return
        }
        scope.launch {
            starting = true
            // 用到才拷：平时躺在私有目录 这里才进 /data/local/tmp
            val targetTmp = Logcat.stageTarget(LogConfig.targetPath)
            if (targetTmp == null) {
                starting = false
                toast(context, "待测apk复制失败 重新选一次")
                return@launch
            }
            val rescueTmp = if (autoReplace) {
                Logcat.stageRescue(LogConfig.rescuePath) ?: run {
                    starting = false
                    toast(context, "救砖apk复制失败 重新选一次")
                    return@launch
                }
            } else {
                ""
            }
            val s = Logcat.start(
                pkg = LogConfig.pkg.trim(),
                waitSeconds = LogConfig.waitSeconds,
                seconds = LogConfig.seconds,
                targetTmp = targetTmp,
                rescueTmp = rescueTmp,
                dir = state.logDir,
            )
            starting = false
            if (s == null) {
                toast(context, "测试没起来 检查 root 授权")
            } else {
                session = s
                running = true
                logLines = emptyList()
                logSize = 0L
            }
        }
    }

    fun stopTest() {
        scope.launch {
            Logcat.stop()
            running = false
            session = null
            logLines = emptyList()
        }
    }

    ScrollPage(
        title = "主页",
        subtitle = "v${AppVersion.name}",
        subtitleExtra = {
            AnimatedVisibility(
                visible = latest != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                // 平时整块不占位 只有查到新版本才冒出来
                Row(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(UpdateGreen)
                        .clickable(onClick = onOpenRelease)
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
                        "有新版本可用",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
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
                            if (running) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Text(
                            if (running) "测试中" else "空闲",
                            style = MiuixTheme.textStyles.title2,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            if (running) {
                                append(LogConfig.pkg.ifBlank { "全部进程" })
                                append(" · 等待 ").append(LogConfig.waitSeconds).append(" 秒")
                                append(" · 抓取 ").append(LogConfig.seconds).append(" 秒")
                            } else {
                                append("未在运行")
                            }
                            append(" · 自动替换").append(if (autoReplace) "开" else "关")
                            if (rescueCount > 0) append(" · 已救砖 ").append(rescueCount).append(" 次")
                            if (logSize > 0) append(" · ").append(Module.fmtSize(logSize))
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
                                    "等待输出…",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.subtitle,
                                )
                            } else {
                                // 日志区自己吃下滚动：到边也不再把页面带着走
                                // nestedScrollToParent = false 时不往父级派发 只留自己的阻尼回弹
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
                                                line.contains("失败") || line.contains("错误") ||
                                                    line.contains("中止") ->
                                                    MiuixTheme.colorScheme.error
                                                line.contains("救砖成功") || line.contains("安装结束") ->
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

        // ---- 文件：先开关 再展开选 apk ----
        item { SmallTitle("文件") }
        item {
            SectionCard {
                SwitchPreference(
                    checked = autoReplace,
                    onCheckedChange = {
                        LogConfig.autoReplace = it
                        LogConfig.save(context)
                    },
                    title = "自动替换",
                    summary = "在apk崩溃后 换回救砖apk",
                )
                AnimatedVisibility(
                    visible = autoReplace,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column {
                        ArrowPreference(
                            title = "救砖apk",
                            summary = if (rescueReady)
                                LogConfig.rescuePath.substringAfterLast('/') +
                                    " · " + Module.fmtSize(LogConfig.rescueSize)
                            else "未选择 · 点击选择崩溃后要装回的 apk",
                            onClick = onPickRescue,
                        )
                    }
                }
                ArrowPreference(
                    title = "待测apk",
                    summary = if (targetReady)
                        LogConfig.targetPath.substringAfterLast('/') +
                            " · " + Module.fmtSize(LogConfig.targetSize)
                    else "未选择 · 点击选择要测试的 apk（系统界面本体请去界面专区）",
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
                            onClick = { startTest() },
                            enabled = canStart,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (starting) "启动中…" else "开始测试")
                        }
                        Button(
                            onClick = { stopTest() },
                            enabled = running && !starting,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("停止")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            rootOk != true -> "等待 root 授权"
                            !targetReady -> "先选待测apk"
                            autoReplace && !rescueReady -> "自动替换开着 还需要选救砖apk"
                            running -> "测试中 手动停止可以提前结束"
                            autoReplace -> "先装待测apk 再抓 " + LogConfig.seconds +
                                " 秒日志 崩了就换回救砖apk"
                            else -> "先装待测apk 再抓 " + LogConfig.seconds +
                                " 秒日志 不执行救砖"
                        },
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.subtitle,
                    )
                }
            }
        }

        // ---- 配置项：目标包名 / 等待时长 / 抓取时长 ----
        item { SmallTitle("配置") }
        item {
            SectionCard {
                ArrowPreference(
                    title = "配置项",
                    summary = "包名 " + LogConfig.pkg.ifBlank { "全部进程" } +
                        " · 等待 " + LogConfig.waitSeconds +
                        " 秒 · 抓取 " + LogConfig.seconds + " 秒",
                    onClick = { showConfig = true },
                )
            }
        }
    }

    // 和设置里的增强救砖弹窗同款：底部滑入的宽对话框
    WindowDialog(
        show = showConfig,
        title = "配置项",
        summary = "目标包名 · 等待时长 · 抓取时长",
        maxWidth = 560.dp,
        onDismissRequest = { showConfig = false },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            TextField(
                value = LogConfig.pkg,
                onValueChange = {
                    LogConfig.pkg = it.trim()
                    LogConfig.save(context)
                },
                label = "目标包名",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            SliderInputPreference(
                title = "等待时长",
                summary = "开抓前等多久 留给 apk 安装",
                value = LogConfig.waitSeconds.toFloat(),
                onValueChange = { LogConfig.waitSeconds = it.roundToInt() },
                onValueChangeFinished = { LogConfig.save(context) },
                valueRange = Logcat.MIN_WAIT.toFloat()..Logcat.MAX_WAIT.toFloat(),
                steps = Logcat.MAX_WAIT - Logcat.MIN_WAIT - 1,
                valueText = "${LogConfig.waitSeconds} 秒",
                inputUnit = "秒",
                // 对话框里不要卡片那层 16dp 内缩 滑轨和上面的包名输入框等宽
                insideMargin = PaddingValues(horizontal = 0.dp, vertical = 14.dp),
            )
            SliderInputPreference(
                title = "抓取时长",
                summary = "到点自动结束 也可以随时手动停止",
                value = LogConfig.seconds.toFloat(),
                onValueChange = { LogConfig.seconds = it.roundToInt() },
                onValueChangeFinished = { LogConfig.save(context) },
                valueRange = Logcat.MIN_SECONDS.toFloat()..Logcat.MAX_SECONDS.toFloat(),
                steps = Logcat.MAX_SECONDS - Logcat.MIN_SECONDS - 1,
                valueText = "${LogConfig.seconds} 秒",
                inputUnit = "秒",
                insideMargin = PaddingValues(horizontal = 0.dp, vertical = 14.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = "完成",
                onClick = { showConfig = false },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

private fun toast(context: Context, msg: String) {
    if (msg.isNotBlank()) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
