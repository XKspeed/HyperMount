package com.xkspeed.HyperMount.ui

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xkspeed.HyperMount.core.CRASH_LIMIT_MANUAL
import com.xkspeed.HyperMount.core.AppLog
import com.xkspeed.HyperMount.core.LogLevel
import com.xkspeed.HyperMount.core.ModuleState
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.ui.components.InfoRow
import com.xkspeed.HyperMount.ui.components.SectionCard
import com.xkspeed.HyperMount.ui.components.SliderInputPreference
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import com.xkspeed.HyperMount.ui.components.LiquidSelectHost
import com.xkspeed.HyperMount.ui.components.LiquidSelectPreference
import com.xkspeed.HyperMount.ui.components.rememberLiquidSelectState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt

private val LOG_MODE_LABELS = listOf("完全禁用", "仅日志", "日志 + 救砖")
private val LOG_MODE_SUMMARIES = listOf(
    "不记录日志 也不启动守护",
    "只记录日志 不执行救砖",
    "音量+ 触发救砖 音量- 取消",
)

// 增强救砖每次开启都会弹警告

@Composable
fun SettingsPage(
    padding: PaddingValues,
    state: ModuleState,
    onSetConf: (String, String) -> Unit,
    onRescueToggle: (Boolean) -> Unit,
    onSetCrashLimit: (Int) -> Unit,
    onOpenUiSettings: () -> Unit = {},
    onOpenDebugLog: () -> Unit = {},
    onOpenAppLog: () -> Unit = {},
    onOpenVolumeCheck: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    checkingUpdate: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // 下拉只有 0 1 2 三档 增强救砖把 2 提到 3
    var logModeIndex by remember(state.logMode) {
        mutableIntStateOf(
            (state.logMode.toIntOrNull() ?: 0).coerceIn(0, 3).let { if (it >= 2) 2 else it }
        )
    }
    var enhanced by remember(state.logMode) {
        mutableStateOf((state.logMode.toIntOrNull() ?: 0) >= 3)
    }
    var askEnhance by remember { mutableStateOf(false) }

    // MT 不交目录时的兜底：手动填路径（默认填当前值）
    var showManualDir by remember { mutableStateOf(false) }
    var manualDirText by remember(state.logDir) { mutableStateOf(state.logDir) }

    // SAF 目录选择器：选完直接落盘 不用再点保存
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // 这几条是排查「选了没生效」的关键：返回码、原始 Intent 全貌、解析出的真实路径
        AppLog.i("选目录", "返回 resultCode=${result.resultCode}")
        val pick = resolveFolderResult(context, result.data)
        AppLog.i("选目录", "来源=${pick.source} path=${pick.path}")
        if (pick.dump.isNotBlank()) AppLog.d("选目录", "原始返回 ${pick.dump}")
        if (result.resultCode != Activity.RESULT_OK) {
            AppLog.w("选目录", "用户取消")
            return@rememberLauncherForActivityResult
        }
        val path = pick.path
        if (path == null) {
            // SAF 只给系统认识的位置 解不出来就让人手填
            AppLog.w("选目录", "这个位置解析不出真实路径 改手动填写")
            toast(context, "这个位置拿不到真实路径 手动填一下")
            manualDirText = state.logDir
            showManualDir = true
            return@rememberLauncherForActivityResult
        }
        AppLog.i("选目录", "解析出 $path（来源 ${pick.source}）")
        AppLog.i("选目录", "提交 LOG_DIR=$path")
        onSetConf("LOG_DIR", path)
    }

    fun pickLogFolder() {
        val intent = buildFolderPickIntent(context)
        AppLog.i("选目录", "拉起 ${intent.action}")
        runCatching { folderPicker.launch(intent) }
            .onFailure {
                AppLog.e("选目录", "启动选择器失败", it)
                toast(context, "打不开文件选择器")
            }
    }

    fun applyMode(value: Int) {
        logModeIndex = if (value >= 2) 2 else value
        enhanced = value >= 3
        onSetConf("LOG_MODE", value.toString())
        when {
            // 增强救砖自己就是靠崩溃次数自动救的
            // 系统救援不屏蔽 阈值还没到 SystemUI 就被系统收走了
            value >= 3 -> onRescueToggle(true)
            // 日志 + 救砖靠音量键：上限抬到 30 才等得到人按下去
            value >= 2 -> onSetCrashLimit(CRASH_LIMIT_MANUAL)
        }
    }

    // 老配置兜底：模式已经是「增强救砖」或「日志 + 救砖」 上限却还是旧的 12
    // 进设置页纠正一次 之后用户手动改不再干预
    var synced by remember { mutableStateOf(false) }
    LaunchedEffect(state.logMode, state.crashLimit, state.rescueOff) {
        if (synced) return@LaunchedEffect
        val mode = state.logMode.toIntOrNull() ?: 0
        when {
            // 增强救砖必须处于屏蔽状态
            mode >= 3 && !state.rescueOff -> {
                synced = true
                onRescueToggle(true)
            }
            // 日志 + 救砖回到 30
            mode == 2 && !state.rescueOff &&
                state.crashLimit !in listOf("", "unknown", CRASH_LIMIT_MANUAL.toString()) -> {
                synced = true
                onSetCrashLimit(CRASH_LIMIT_MANUAL)
            }
            state.crashLimit.isNotBlank() -> synced = true
        }
    }

    // 拖动过程中只改本地值 松手才落盘 避免每帧一次 sed
    var keyTimeout by remember(state.keyTimeout) {
        mutableFloatStateOf((state.keyTimeout.toIntOrNull() ?: 30).coerceIn(10, 60).toFloat())
    }
    var fatalThreshold by remember(state.fatalThreshold) {
        mutableFloatStateOf((state.fatalThreshold.toIntOrNull() ?: 3).coerceIn(1, 10).toFloat())
    }

    val select = rememberLiquidSelectState()

    ScrollPage(
        title = "设置",
        // 与主页/关于一致：底栏高度（70dp）已由外层 pagePadding 给出 这里只加一点呼吸
        bottomPadding = padding.calculateBottomPadding() + 16.dp,
        overlay = { bd -> LiquidSelectHost(select, bd, bottomInset = padding.calculateBottomPadding()) },
    ) {
                item { SmallTitle("运行") }
                item {
                    SectionCard {
                        LiquidSelectPreference(
                            title = "运行模式",
                            summary = LOG_MODE_SUMMARIES[logModeIndex],
                            items = LOG_MODE_LABELS,
                            selectedIndex = logModeIndex,
                            onSelectedIndexChange = { applyMode(it) },
                            state = select,
                        )
                        // 只要带日志就联动出现 点了直接改 LOG_DIR
                        AnimatedVisibility(
                            visible = logModeIndex >= 1,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            Column {
                                ArrowPreference(
                                    title = "日志目录",
                                    summary = state.logDir.ifBlank { "未设置" },
                                    // 点一下直接跳 MT 选文件夹 不再先弹输入框
                                    onClick = { pickLogFolder() },
                                )
                            }
                        }
                        // 等待超时只在仅日志模式有意义 救砖模式一直等到按键
                        AnimatedVisibility(
                            visible = logModeIndex == 1,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            Column {
                                SliderInputPreference(
                                    title = "等待超时",
                                    summary = "替换界面后等 SystemUI 重启的秒数",
                                    value = keyTimeout,
                                    onValueChange = { keyTimeout = it },
                                    onValueChangeFinished = {
                                        onSetConf("KEY_TIMEOUT", keyTimeout.roundToInt().toString())
                                    },
                                    valueRange = 10f..60f,
                                    steps = 9,
                                    valueText = "${keyTimeout.roundToInt()} 秒",
                                    inputUnit = "秒",
                                )
                            }
                        }
                    }
                }

                // 选了日志 + 救砖才出现 和主题页选莫奈后多出的那块一样
                item {
                    AnimatedVisibility(
                        visible = logModeIndex == 2,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column {
                            SmallTitle("自动救砖")
                            SectionCard {
                                // 救砖靠音量键触发 校验入口就放这一组最上面
                                ArrowPreference(
                                    title = "音量键校验",
                                    summary = if (state.volumeAssessed)
                                        "已识别"
                                    else "未校验 · 救砖时音量键可能没反应",
                                    onClick = onOpenVolumeCheck,
                                )
                                SwitchPreference(
                                    checked = enhanced,
                                    onCheckedChange = { on ->
                                        if (on) {
                                            askEnhance = true
                                        } else {
                                            applyMode(2)
                                        }
                                    },
                                    title = "增强救砖",
                                    summary = "崩溃达到阈值自动替换 SystemUI",
                                )
                                SliderInputPreference(
                                    title = "崩溃阈值",
                                    summary = if (enhanced)
                                        "累计多少条 fatal 日志后自动救砖"
                                    else
                                        "开启增强救砖后生效",
                                    value = fatalThreshold,
                                    onValueChange = { fatalThreshold = it },
                                    onValueChangeFinished = {
                                        onSetConf(
                                            "FATAL_THRESHOLD",
                                            fatalThreshold.roundToInt().toString(),
                                        )
                                    },
                                    enabled = enhanced,
                                    valueRange = 1f..10f,
                                    steps = 8,
                                    valueText = "${fatalThreshold.roundToInt()} 次",
                                    inputUnit = "次",
                                )
                                // 增强救砖和屏蔽系统救援绑死：开了增强就必须屏蔽
                                // 否则系统先崩到上限 自动救砖还没轮到就已经把界面收了
                                SwitchPreference(
                                    checked = enhanced || state.rescueOff,
                                    onCheckedChange = { on ->
                                        if (!enhanced) {
                                            if (on) onRescueToggle(true)
                                            else onSetCrashLimit(CRASH_LIMIT_MANUAL)
                                        }
                                    },
                                    enabled = !enhanced,
                                    title = "屏蔽系统救援",
                                    summary = if (enhanced)
                                        "增强救砖已开启 强制屏蔽不可关闭"
                                    else
                                        "拉高 AMS 崩溃次数上限 关闭后回到 $CRASH_LIMIT_MANUAL",
                                )
                            }
                        }
                    }
                }

                item { SmallTitle("外观") }
                item {
                    SectionCard {
                        ArrowPreference(
                            title = "界面与主题",
                            summary = "主题模式 · 莫奈取色 · 底栏模糊 · 悬浮底栏",
                            onClick = onOpenUiSettings,
                        )
                    }
                }

                item { SmallTitle("调试") }
                item {
                    SectionCard {
                        // 全局日志的等级：和运行模式同一套下拉
                        // 排查「改了没生效」这类问题时调高 平时留常规就够
                        LiquidSelectPreference(
                            title = "日志等级",
                            summary = "当前 ${AppLog.level.label} · 记录到应用日志",
                            items = LOG_LEVEL_LABELS,
                            selectedIndex = AppLog.level.ordinal,
                            onSelectedIndexChange = {
                                AppLog.setLevel(context, LogLevel.entries[it])
                            },
                            state = select,
                        )
                        SwitchPreference(
                            checked = state.debug,
                            onCheckedChange = { onSetConf("DEBUG", if (it) "true" else "false") },
                            title = "调试日志",
                            summary = "在 config/debug.log 里记录每一步动作",
                        )
                        ArrowPreference(
                            title = "调试日志预览",
                            summary = if (state.debug) "查看脚本每一步的动作" else "先打开上面的开关",
                            onClick = onOpenDebugLog,
                        )
                        ArrowPreference(
                            title = "应用日志",
                            summary = AppLog.file()?.length()?.takeIf { it > 0 }
                                ?.let { "${it / 1024} KB · 点此查看运行流水" }
                                ?: "还没有日志",
                            onClick = onOpenAppLog,
                        )
                    }
                }

                item { SmallTitle("运行数据") }
                item {
                    SectionCard {
                        InfoRow("已救砖", "${state.protectCount} 次")
                        InfoRow("SystemUI", if (state.pid.isNotBlank()) "PID ${state.pid}" else "未运行")
                        InfoRow("崩溃上限", state.rescueLimitText)
                    }
                }

                // 关于整页挪成二级页 三行并成一张卡：关于 / 检查更新 / 自动检查更新
                item { SmallTitle("关于") }
                item {
                    SectionCard {
                        ArrowPreference(
                            title = "关于",
                            summary = "版本 · 开源许可",
                            onClick = onOpenAbout,
                        )
                        // 检查更新：普通基础组件 没有副标题 右边只留箭头
                        ArrowPreference(
                            title = if (checkingUpdate) "检查中…" else "检查更新",
                            enabled = !checkingUpdate,
                            onClick = onCheckUpdate,
                        )
                        SwitchPreference(
                            checked = UiConfigState.autoCheckUpdate,
                            onCheckedChange = {
                                UiConfigState.autoCheckUpdate = it
                                UiConfigState.save(context)
                            },
                            title = "自动检查更新",
                        )
                    }
                }
            }

    // 警告用宽对话框 标题和副标题走 Miuix 自己的样式
    WindowDialog(
        show = askEnhance,
        title = "警告",
        summary = "该功能安全性未知 谨慎开启",
        maxWidth = 560.dp,
        onDismissRequest = { askEnhance = false },
    ) {
        Text(
            "开启后崩溃次数达到阈值会自动替换 SystemUI 不需要按音量键\n" +
                "替换失败可能导致无法开机 建议先确认救援 APK 可用",
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.subtitle,
        )
        Spacer(Modifier.height(20.dp))
        // 按钮用 Miuix 的 TextButton 和示例 dialog 一致 圆角卡片观感
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = "取消",
                onClick = { askEnhance = false },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = "继续开启",
                onClick = {
                    askEnhance = false
                    applyMode(3)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }

    // MT 回不来目录路径时的兜底：手动填（默认带上当前配置值）
    WindowDialog(
        show = showManualDir,
        title = "日志目录",
        summary = "目录不存在会自动创建 改完对下一次抓取生效",
        maxWidth = 560.dp,
        onDismissRequest = { showManualDir = false },
    ) {
        TextField(
            value = manualDirText,
            onValueChange = { manualDirText = it },
            label = "目录路径",
            useLabelAsPlaceholder = true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = "取消",
                onClick = { showManualDir = false },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = "保存",
                onClick = {
                    val p = manualDirText.trim()
                    showManualDir = false
                    if (p.isEmpty()) {
                        toast(context, "路径不能为空")
                        return@TextButton
                    }
                    AppLog.i("选目录", "手动提交 LOG_DIR=$p")
                    onSetConf("LOG_DIR", p)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

private fun toast(context: Context, msg: String) {
    if (msg.isNotBlank()) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
