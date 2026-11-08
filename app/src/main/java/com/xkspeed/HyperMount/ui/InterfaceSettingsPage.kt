package com.xkspeed.HyperMount.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.xkspeed.HyperMount.core.CRASH_LIMIT_MANUAL
import com.xkspeed.HyperMount.core.DaemonService
import com.xkspeed.HyperMount.core.DaemonStatus
import com.xkspeed.HyperMount.core.Module
import com.xkspeed.HyperMount.core.ModuleState
import com.xkspeed.HyperMount.core.WatchStatus
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.ui.components.LiquidSelectHost
import com.xkspeed.HyperMount.ui.components.LiquidSelectPreference
import com.xkspeed.HyperMount.ui.components.SectionCard
import com.xkspeed.HyperMount.ui.components.StatusDot
import com.xkspeed.HyperMount.ui.components.rememberLiquidSelectState
import com.xkspeed.HyperMount.ui.components.SliderInputPreference
import com.xkspeed.HyperMount.ui.components.sliderTapSeek
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt

/**
 * 守护时长的四个档位（秒）
 *
 * 1 小时 / 2 小时 / 自定义 / 无限制 —— 滑条上四个点等距排开，
 * 自定义不占固定值 选到它再弹框填分钟。
 */
private val WATCH_OPTIONS = listOf(60L * 60, 2 * 60L * 60, -1L, 0L)
private const val WATCH_CUSTOM = -1L

/** 运行模式三档：增强救砖是 LOG_MODE=3，下拉里按「日志 + 救砖」显示 */
private val MODE_LABELS = listOf("完全禁用", "仅日志", "日志 + 救砖")
private val MODE_SUMMARIES = listOf(
    "不记录日志 也不启动守护",
    "只记录日志 不执行救砖",
    "音量+ 触发救砖 音量- 取消",
)

/** 上一次选的时长：存秒数 自定义的分钟数单独存一份 下次进来还原成同一个档 */
private object WatchPrefs {
    private const val NAME = "watch_config"

    fun limit(context: Context): Long =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getLong("limit", 0L)

    fun setLimit(context: Context, value: Long) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putLong("limit", value).apply()
    }

    fun customMinutes(context: Context): Int =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getInt("custom_min", 60).coerceIn(1, 1440)

    fun setCustomMinutes(context: Context, value: Int) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putInt("custom_min", value).apply()
    }
}

/** 秒数落到档位下标：不在固定档上的都算自定义 */
private fun optionIndex(limitSec: Long): Int {
    val i = WATCH_OPTIONS.indexOf(limitSec)
    return if (i >= 0) i else WATCH_OPTIONS.indexOf(WATCH_CUSTOM)
}

private fun optionText(index: Int): String = when (index) {
    0 -> "1 小时"
    1 -> "2 小时"
    2 -> "自定义"
    else -> "无限制"
}

/** 剩余时间：超过一小时才带小时位 */
private fun fmtRemain(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    fun two(v: Long) = if (v < 10) "0$v" else "$v"
    return if (h > 0) "$h:${two(m)}:${two(ss)}" else "${two(m)}:${two(ss)}"
}

/**
 * 界面设置（界面页右上角进入）
 *
 * 这里收着原来散在界面页上的东西：全局常驻救砖的开关与时长，
 * 以及救砖逻辑那四行（音量键校验 / 增强救砖 / 崩溃阈值 / 屏蔽系统救援）。
 * 界面页只留文件选择、开始测试和设备信息，页面本身清爽一点。
 */
@Composable
fun InterfaceSettingsPage(
    state: ModuleState,
    onBack: () -> Unit,
    onOpenVolumeCheck: () -> Unit,
    onSetConf: (String, String) -> Unit,
    onRescueToggle: (Boolean) -> Unit,
    onSetCrashLimit: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 运行模式只有 0/1/2 三档 增强救砖把 2 提到 3
    val logMode = state.logMode.toIntOrNull() ?: 0

    // ---- 乐观更新 ----
    // 配置写下去要等 App 回读（1 秒）才反映到 state 上，直接用 state 派生
    // 会出现「点了没反应 过一会儿才变」。这里和原设置页一样先改本地值，
    // 回读回来再以 state 为准（写失败就自动还原成实际配置）
    var modeIndex by remember(state.logMode) {
        mutableIntStateOf((state.logMode.toIntOrNull() ?: 0).coerceIn(0, 3).let {
            if (it >= 2) 2 else it
        })
    }
    var enhanced by remember(state.logMode) {
        mutableStateOf((state.logMode.toIntOrNull() ?: 0) >= 3)
    }
    var rescueOff by remember(state.rescueOff) { mutableStateOf(state.rescueOff) }

    // ---- 全局常驻救砖 ----
    var status by remember { mutableStateOf(WatchStatus()) }
    // 还没读到真实状态前别急着说「模块版本不支持」 那一百来毫秒会闪一下红点
    var loaded by remember { mutableStateOf(false) }
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    var busy by remember { mutableStateOf(false) }
    var durationIndex by remember {
        mutableFloatStateOf(optionIndex(WatchPrefs.limit(context)).toFloat())
    }
    var customMinutes by remember { mutableIntStateOf(WatchPrefs.customMinutes(context)) }
    var showCustom by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }

    val index = durationIndex.roundToInt().coerceIn(0, WATCH_OPTIONS.lastIndex)
    val selected = WATCH_OPTIONS[index]
    val seconds = if (selected == WATCH_CUSTOM) customMinutes * 60L else selected
    val durationText = if (selected == WATCH_CUSTOM) {
        "自定义 · $customMinutes 分钟"
    } else {
        optionText(index)
    }

    // 进页面先对一次 之后运行期间每秒刷一次剩余时间（停止就不轮询）
    LaunchedEffect(Unit) {
        status = Module.watchStatus()
        nowSec = System.currentTimeMillis() / 1000
        loaded = true
    }
    // 剩余时间用本地时钟自己数 每 5 秒才对一次真实状态
    // 免得每秒都 fork 一次 sh（看个倒计时没必要这么费）
    LaunchedEffect(status.running) {
        var tick = 0
        while (status.running) {
            delay(1000)
            nowSec = System.currentTimeMillis() / 1000
            tick++
            if (tick % 5 == 0) status = Module.watchStatus()
        }
    }

    fun persistDuration(i: Int, minutes: Int) {
        val value = if (WATCH_OPTIONS[i] == WATCH_CUSTOM) minutes * 60L else WATCH_OPTIONS[i]
        WatchPrefs.setLimit(context, value)
    }

    /** 选到某一档：自定义弹输入框 其余直接落盘 */
    fun chooseDuration(i: Int) {
        val idx = i.coerceIn(0, WATCH_OPTIONS.lastIndex)
        durationIndex = idx.toFloat()
        if (WATCH_OPTIONS[idx] == WATCH_CUSTOM) {
            customText = ""
            showCustom = true
        } else {
            persistDuration(idx, customMinutes)
        }
    }

    fun toggleWatch(on: Boolean) {
        if (busy) return
        // --now 那一轮沿用原逻辑：模式不对根本没日志可判 起了也是白跑
        if (on && modeIndex < 2) {
            toast(context, "先在设置把运行模式改成「日志 + 救砖」")
            return
        }
        scope.launch {
            busy = true
            val out = if (on) Module.watchStart(seconds.toInt()) else Module.watchStop()
            status = Module.watchStatus()
            nowSec = System.currentTimeMillis() / 1000
            busy = false
            if (!out.contains("OK")) {
                toast(context, firstLine(out))
            } else if (on != status.running) {
                toast(context, if (on) "没能启动 检查一下 root 授权" else "没能停止")
            } else if (on) {
                // 守护跑起来就把前台服务拉起来 切后台/锁屏才不会被系统清掉
                DaemonService.start(context)
            } else {
                // 停守护时如果测试还在跑 服务留着 让它自己收尾
                val st = runCatching { Module.daemonStatus() }.getOrDefault(DaemonStatus())
                if (!st.testing) DaemonService.stop(context)
            }
        }
    }

    // ---- 救砖逻辑（从界面页搬过来）----
    var askEnhance by remember { mutableStateOf(false) }
    var fatalThreshold by remember(state.fatalThreshold) {
        mutableFloatStateOf((state.fatalThreshold.toIntOrNull() ?: 3).coerceIn(1, 10).toFloat())
    }

    // 老配置兜底：模式已经是「增强救砖」或「日志 + 救砖」 上限却还是旧的 12
    // 进这个页面纠正一次 之后用户手动改不再干预
    var synced by remember { mutableStateOf(false) }
    LaunchedEffect(state.logMode, state.crashLimit, state.rescueOff) {
        if (synced) return@LaunchedEffect
        when {
            logMode >= 3 && !state.rescueOff -> {
                synced = true
                onRescueToggle(true)
            }
            logMode == 2 && !state.rescueOff &&
                state.crashLimit !in listOf("", "unknown", CRASH_LIMIT_MANUAL.toString()) -> {
                synced = true
                onSetCrashLimit(CRASH_LIMIT_MANUAL)
            }
            state.crashLimit.isNotBlank() -> synced = true
        }
    }

    val select = rememberLiquidSelectState()

    fun applyMode(value: Int) {
        // 先改界面 再落盘
        modeIndex = if (value >= 2) 2 else value
        enhanced = value >= 3
        onSetConf("LOG_MODE", value.toString())
        when {
            // 增强救砖自己就是靠崩溃次数自动救的 系统救援不屏蔽会先被系统收走
            value >= 3 -> {
                rescueOff = true
                onRescueToggle(true)
            }
            // 日志 + 救砖靠音量键 上限抬到 30 才等得到人按下去
            value >= 2 -> onSetCrashLimit(CRASH_LIMIT_MANUAL)
        }
    }

    ScrollPage(
        title = "界面设置",
        subtitle = "常驻救砖与救砖逻辑",
        onBack = onBack,
        bottomPadding = WindowInsets.navigationBars.asPaddingValues()
            .calculateBottomPadding() + 24.dp,
        overlay = { bd ->
            LiquidSelectHost(
                select,
                bd,
                bottomInset = WindowInsets.navigationBars.asPaddingValues()
                    .calculateBottomPadding(),
            )
        },
    ) {
        // ---- 运行模式 ----
        item { SmallTitle("运行") }
        item {
            SectionCard {
                LiquidSelectPreference(
                    title = "运行模式",
                    summary = MODE_SUMMARIES[modeIndex],
                    items = MODE_LABELS,
                    selectedIndex = modeIndex,
                    onSelectedIndexChange = { applyMode(it) },
                    state = select,
                )
            }
        }

        // ---- 常驻状态 ----
        item { SmallTitle("全局常驻救砖") }
        item {
            // 和界面页那张状态卡同款：圆点 + 大标题 + 一行说明
            val unsupported = loaded && !status.supported
            val live = loaded && status.supported && status.running
            val dot = when {
                !loaded -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                unsupported -> MiuixTheme.colorScheme.error
                live -> MiuixTheme.colorScheme.primary
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            }
            val head = when {
                !loaded -> "读取中"
                unsupported -> "模块版本不支持"
                live -> "全局救砖中"
                else -> "未启用"
            }
            val sub = when {
                !loaded -> "正在检查模块是否带常驻守护"
                unsupported -> "当前模块没有常驻守护 更新模块后再用"
                !live -> "开启后界面反复重启会自动救砖"
                status.remaining(nowSec) < 0 -> "不限制时长"
                else -> "剩余 ${fmtRemain(status.remaining(nowSec))}"
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(dot)
                        Text(
                            head,
                            style = MiuixTheme.textStyles.title2,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            append(sub)
                            if (live && status.count > 0) append(" · 本轮重启 ${status.count} 次")
                        },
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.subtitle,
                    )
                }
            }
        }
        item {
            SectionCard {
                SwitchPreference(
                    checked = status.running,
                    enabled = loaded && status.supported && !busy,
                    onCheckedChange = { toggleWatch(it) },
                    title = "全局守护",
                )

                // 时长：四个点等距 点/拖到哪档就是哪档 自定义松手弹输入框
                if (loaded && status.supported) Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 4.dp, bottom = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "守护时长",
                            style = MiuixTheme.textStyles.main,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            durationText,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.subtitle,
                        )
                    }
                    Slider(
                        value = durationIndex,
                        onValueChange = { durationIndex = it },
                        onValueChangeFinished = { chooseDuration(durationIndex.roundToInt()) },
                        valueRange = 0f..WATCH_OPTIONS.lastIndex.toFloat(),
                        steps = WATCH_OPTIONS.size - 2,
                        showKeyPoints = true,
                        hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .sliderTapSeek(WATCH_OPTIONS.lastIndex) { chooseDuration(it) },
                    )
                    DurationTicks(selected = index, onSelect = { chooseDuration(it) })
                }
            }
        }

        // ---- 救砖逻辑 ----
        // 和原来一样：选了「日志 + 救砖」才出现
        // 单条 item 里套 AnimatedVisibility LazyListScope 里不能再拆 item
        item {
            // 用 AnimatedVisibility 而不是拆成多个 item：LazyListScope 里
            // 拆 item 就没法做高度动画 展开收起会硬跳
            AnimatedVisibility(
                visible = modeIndex >= 2,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    SmallTitle("救砖逻辑")
                    SectionCard {
                        ArrowPreference(
                            title = "音量键校验",
                            summary = if (state.volumeAssessed) {
                                "已识别"
                            } else {
                                "未校验 · 救砖时音量键可能没反应"
                            },
                            onClick = onOpenVolumeCheck,
                        )
                        SwitchPreference(
                            checked = enhanced,
                            onCheckedChange = { on ->
                                if (on) {
                                    askEnhance = true
                                } else {
                                    // 退回「日志 + 救砖」 先改界面 再落盘
                                    enhanced = false
                                    modeIndex = 2
                                    onSetConf("LOG_MODE", "2")
                                    onSetCrashLimit(CRASH_LIMIT_MANUAL)
                                }
                            },
                            title = "增强救砖",
                            summary = "崩溃达到阈值自动替换 SystemUI",
                        )
                        SliderInputPreference(
                            title = "崩溃阈值",
                            summary = if (enhanced) {
                                "累计多少条 fatal 日志后自动救砖"
                            } else {
                                "开启增强救砖后生效"
                            },
                            value = fatalThreshold,
                            defaultValue = 3f,
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
                            checked = enhanced || rescueOff,
                            onCheckedChange = { on ->
                                if (!enhanced) {
                                    rescueOff = on
                                    if (on) onRescueToggle(true)
                                    else onSetCrashLimit(CRASH_LIMIT_MANUAL)
                                }
                            },
                            enabled = !enhanced,
                            title = "屏蔽系统救援",
                        )
                    }
                }
            }
        }
    }

    // 增强救砖的警告：和设置页以前那句一样 安全性未知必须确认
    if (askEnhance) {
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
                        enhanced = true
                        modeIndex = 2
                        rescueOff = true
                        onSetConf("LOG_MODE", "3")
                        onRescueToggle(true)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }

    // 自定义时长：只收数字 1～1440 分钟
    if (showCustom) {
        WindowDialog(
            show = showCustom,
            title = "自定义时长",
            summary = "1～1440 分钟 到点自动停止",
            maxWidth = 560.dp,
            onDismissRequest = {
                showCustom = false
                durationIndex = optionIndex(WatchPrefs.limit(context)).toFloat()
            },
        ) {
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            // 弹窗进场动画走完再抢焦点 否则键盘会被收起
            LaunchedEffect(Unit) {
                delay(200)
                runCatching { focus.requestFocus() }
                keyboard?.show()
            }
            TextField(
                value = customText,
                onValueChange = { it.filter { c -> c.isDigit() }.take(4) },
                label = "分钟",
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "取消",
                    onClick = {
                        showCustom = false
                        durationIndex = optionIndex(WatchPrefs.limit(context)).toFloat()
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "保存",
                    onClick = {
                        val minutes = customText.toIntOrNull()
                        if (minutes == null || minutes !in 1..1440) {
                            toast(context, "填个 1～1440 的整数")
                        } else {
                            customMinutes = minutes
                            WatchPrefs.setCustomMinutes(context, minutes)
                            WatchPrefs.setLimit(context, minutes * 60L)
                            durationIndex = WATCH_OPTIONS.indexOf(WATCH_CUSTOM).toFloat()
                            showCustom = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/**
 * 滑条下面的四个档位文字
 *
 * 位置按滑轨算：两端各让出半个 thumb 圆钮 文字中心也照这个内缩
 * 点哪档跳哪档 不用非得把圆钮拖过去
 */
@Composable
private fun DurationTicks(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val inset = with(density) { SliderDefaults.MinHeight.toPx() / 2f }
    val active = MiuixTheme.colorScheme.primary
    val idle = MiuixTheme.colorScheme.onSurfaceVariantSummary

    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        content = {
            WATCH_OPTIONS.forEachIndexed { i, _ ->
                Text(
                    text = optionText(i),
                    color = if (i == selected) active else idle,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable { onSelect(i) }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints()) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            val last = placeables.lastIndex
            placeables.forEachIndexed { i, p ->
                val center = if (last <= 0) width / 2f else inset + (width - 2 * inset) * i / last
                val x = (center - p.width / 2f).roundToInt()
                    .coerceIn(0, (width - p.width).coerceAtLeast(0))
                p.place(x, 0)
            }
        }
    }
}

private fun toast(context: Context, msg: String) {
    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
}

private fun firstLine(out: String): String =
    out.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "操作失败"
