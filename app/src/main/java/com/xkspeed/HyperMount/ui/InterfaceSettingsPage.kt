package com.xkspeed.HyperMount.ui

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import com.xkspeed.HyperMount.ui.components.sliderTapFraction
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt
import com.xkspeed.HyperMount.ui.util.stableNavigationBarHeight

/**
 * 守护时长：1 小时到 6 小时连续可拖，最右留一小截是「无限制」
 *
 * 老的「1h / 2h / 自定义 / 无限制」四档取消：中间的分钟数直接拖出来，
 * 锚点只留 2 / 4 / 6 小时和无限制，靠近会被吸过去。
 */
private const val WATCH_MIN_MIN = 60f
private const val WATCH_MAX_MIN = 360f
private const val WATCH_UNLIMITED_MIN = 430f
private val WATCH_NODES = listOf(120f, 240f, 360f, WATCH_UNLIMITED_MIN)

/** 吸附范围（分钟） */
private const val WATCH_SNAP_MIN = 15f

/** 运行模式三档：增强救砖是 LOG_MODE=3，下拉里按「日志 + 救砖」显示 */
private val MODE_LABELS = listOf("完全禁用", "仅日志", "日志 + 救砖")
private val MODE_SUMMARIES = listOf(
    "不记录日志 也不启动守护",
    "只记录日志 不执行救砖",
    "音量+ 触发救砖 音量- 取消",
)

/** 上一次选的时长：存秒数 0 = 无限制 */
private object WatchPrefs {
    private const val NAME = "watch_config"

    fun limit(context: Context): Long =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getLong("limit", 0L)

    fun setLimit(context: Context, value: Long) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putLong("limit", value).apply()
    }
}

private fun watchUnlimited(min: Float): Boolean = min > WATCH_MAX_MIN + 1f

/** 滑条 / 胶囊里显示的时长 */
private fun watchLabel(min: Float): String {
    if (watchUnlimited(min)) return "无限制"
    val total = min.roundToInt()
    val h = total / 60
    val m = total % 60
    return if (m == 0) "$h 小时" else "$h 小时 $m 分"
}

/** 靠近锚点就吸附；拖过 6 小时直接落到无限制 */
private fun snapWatch(min: Float): Float {
    if (min > WATCH_MAX_MIN + WATCH_SNAP_MIN) return WATCH_UNLIMITED_MIN
    val near = WATCH_NODES.minByOrNull { abs(it - min) } ?: return min
    return if (abs(near - min) <= WATCH_SNAP_MIN) near else min
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
    var durationMin by remember {
        val saved = WatchPrefs.limit(context)
        mutableFloatStateOf(
            if (saved <= 0L) WATCH_UNLIMITED_MIN
            else (saved / 60f).coerceIn(WATCH_MIN_MIN, WATCH_MAX_MIN)
        )
    }
    // 胶囊定位要滑条实际宽度；dragging 决定胶囊出不出现
    var sliderWidthPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }

    // 守护判定条件：窗口秒数与窗口内重启次数
    // 拖动时按浮点走（无极档），松手才吸附到最近的整数并落盘
    var watchWindow by remember(state.watchWindow) {
        mutableFloatStateOf((state.watchWindow.toIntOrNull() ?: 30).coerceIn(5, 60).toFloat())
    }
    var watchCount by remember(state.watchCount) {
        mutableFloatStateOf((state.watchCount.toIntOrNull() ?: 3).coerceIn(2, 5).toFloat())
    }

    val seconds = if (watchUnlimited(durationMin)) 0L else durationMin.roundToInt() * 60L

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

    /** 松手 / 点轨道时落盘：无限制存 0，其余存秒 */
    fun persistDuration() {
        val value = if (watchUnlimited(durationMin)) 0L else durationMin.roundToInt() * 60L
        WatchPrefs.setLimit(context, value)
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
        title = "设置",
        onBack = onBack,
        bottomPadding = stableNavigationBarHeight() + 24.dp,
        overlay = { bd ->
            LiquidSelectHost(
                select,
                bd,
                bottomInset = stableNavigationBarHeight(),
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
        // 与下面的「救砖逻辑」一致：只有选了「日志 + 救砖」才展开这块
        item {
            AnimatedVisibility(
                visible = modeIndex >= 2,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    SmallTitle("全局常驻救砖")
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
                    SectionCard {
                        SwitchPreference(
                            checked = status.running,
                            enabled = loaded && status.supported && !busy,
                            onCheckedChange = { toggleWatch(it) },
                            title = "全局守护",
                            // 正下方就是滑条：底部内缩收一半，别把滑条推得太远
                            insideMargin = PaddingValues(
                                start = 16.dp,
                                top = 14.dp,
                                end = 16.dp,
                                bottom = 8.dp,
                            ),
                        )

                        // 时长：1h 到 6h 连续可拖，最右留一截是无限制；2/4/6h 会被吸过去
                        // 当前值只在拖动胶囊上出现，上面不再重复一行
                        // 守护跑着的时候收起来：改了也得关掉再开才生效，摆着反而误导
                        AnimatedVisibility(
                            visible = loaded && status.supported && !status.running,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 10.dp),
                            ) {
                                val bubbleAlpha by animateFloatAsState(
                                    targetValue = if (dragging) 1f else 0f,
                                    animationSpec = tween(120),
                                    label = "watchBubble",
                                )
                                // 跟手必须和 Miuix Slider 内部同一条弹簧，否则胶囊会慢半拍
                                val followMin by animateFloatAsState(
                                    targetValue = durationMin,
                                    animationSpec = if (dragging) {
                                        spring(dampingRatio = 0.9f, stiffness = 1755f)
                                    } else {
                                        spring(dampingRatio = 0.96f, stiffness = 322f)
                                    },
                                    label = "watchFollow",
                                )
                                val fraction = ((followMin - WATCH_MIN_MIN) /
                                    (WATCH_UNLIMITED_MIN - WATCH_MIN_MIN)).coerceIn(0f, 1f)

                                // 节点文字 + 拖动胶囊：和缓存页同一套
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(30.dp)
                                        .onSizeChanged { sliderWidthPx = it.width },
                                ) {
                                    val density = LocalDensity.current
                                    val radius = with(density) { 10.dp.toPx() }
                                    val usable = (sliderWidthPx - 2f * radius).coerceAtLeast(1f)
                                    // 吸住了谁：胶囊停过去 那个节点的文字退场
                                    val taken = if (dragging) {
                                        WATCH_NODES.minByOrNull { abs(it - durationMin) }
                                            ?.takeIf { abs(it - durationMin) <= WATCH_SNAP_MIN }
                                    } else {
                                        null
                                    }

                                    if (sliderWidthPx > 0) {
                                        WATCH_NODES.forEach { node ->
                                            val f = ((node - WATCH_MIN_MIN) /
                                                (WATCH_UNLIMITED_MIN - WATCH_MIN_MIN)).coerceIn(0f, 1f)
                                            val cx = radius + f * usable
                                            val vanish by animateFloatAsState(
                                                targetValue = if (taken != null && abs(taken - node) < 0.5f) 1f else 0f,
                                                animationSpec = tween(120),
                                                label = "watchNode$node",
                                            )
                                            if (vanish < 0.99f) {
                                                Box(
                                                    Modifier
                                                        .align(Alignment.CenterStart)
                                                        .graphicsLayer {
                                                            translationX = cx - size.width / 2f
                                                            alpha = 1f - vanish
                                                            val s = 1f - 0.22f * vanish
                                                            scaleX = s
                                                            scaleY = s
                                                        },
                                                ) {
                                                    Text(
                                                        watchLabel(node),
                                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                        style = MiuixTheme.textStyles.subtitle.copy(fontSize = 10.sp),
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (bubbleAlpha > 0.01f) {
                                        // 实色：背后压着的节点文字不能透出来
                                        val bubbleBg = lerp(
                                            MiuixTheme.colorScheme.surface,
                                            MiuixTheme.colorScheme.primary,
                                            0.16f,
                                        )
                                        val x = radius + fraction * usable
                                        Box(
                                            Modifier
                                                .align(Alignment.CenterStart)
                                                .graphicsLayer { translationX = x - size.width / 2f }
                                                .alpha(bubbleAlpha)
                                                .clip(RoundedCornerShape(50))
                                                .background(bubbleBg)
                                                .padding(horizontal = 9.dp, vertical = 4.dp),
                                        ) {
                                            Text(
                                                watchLabel(durationMin),
                                                color = MiuixTheme.colorScheme.primary,
                                                style = MiuixTheme.textStyles.subtitle.copy(fontSize = 10.sp),
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }

                                Slider(
                                    value = durationMin,
                                    onValueChange = {
                                        dragging = true
                                        durationMin = snapWatch(it)
                                    },
                                    onValueChangeFinished = {
                                        dragging = false
                                        persistDuration()
                                    },
                                    valueRange = WATCH_MIN_MIN..WATCH_UNLIMITED_MIN,
                                    // 不设档位：1 小时到 6 小时之间随便拖
                                    steps = 0,
                                    showKeyPoints = true,
                                    keyPoints = WATCH_NODES,
                                    hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 2.dp)
                                        .sliderTapFraction { f ->
                                            durationMin = snapWatch(
                                                WATCH_MIN_MIN + f * (WATCH_UNLIMITED_MIN - WATCH_MIN_MIN),
                                            )
                                            dragging = false
                                            persistDuration()
                                        },
                                )

                                // 判定条件：一句主文案 + 一句说明，和上面滑条同在一张卡
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                ) {
                                    Text(
                                        "${watchWindow.roundToInt()} 秒内 SystemUI 重启 " +
                                            "${watchCount.roundToInt()} 次则进入守护",
                                        style = MiuixTheme.textStyles.subtitle,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "此时便可以等待自动救援 / 音量键救砖",
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        style = MiuixTheme.textStyles.subtitle,
                                    )
                                }

                                // 两条滑条都按无极档走：拖动时值是浮点，松手才吸到最近的整数。
                                // 外层 Column 已经给了 16dp，insideMargin 再收掉水平方向，否则双倍内缩。
                                SliderInputPreference(
                                    title = "监控时长",
                                    value = watchWindow,
                                    defaultValue = 30f,
                                    onValueChange = { watchWindow = it },
                                    onValueChangeFinished = {
                                        val v = watchWindow.roundToInt().coerceIn(5, 60)
                                        watchWindow = v.toFloat()
                                        onSetConf("WATCH_WINDOW", v.toString())
                                    },
                                    valueRange = 5f..60f,
                                    // 不设档位：5~60 秒之间连续拖，松手吸附到整秒
                                    steps = 0,
                                    valueText = "最近 ${watchWindow.roundToInt()} 秒",
                                    inputRange = 5f..60f,
                                    inputUnit = "秒",
                                    insideMargin = PaddingValues(top = 8.dp, bottom = 6.dp),
                                )
                                SliderInputPreference(
                                    title = "崩溃次数",
                                    value = watchCount,
                                    defaultValue = 3f,
                                    onValueChange = { watchCount = it },
                                    onValueChangeFinished = {
                                        val v = watchCount.roundToInt().coerceIn(2, 5)
                                        watchCount = v.toFloat()
                                        onSetConf("WATCH_COUNT", v.toString())
                                    },
                                    valueRange = 2f..5f,
                                    steps = 0,
                                    valueText = "${watchCount.roundToInt()} 次",
                                    inputRange = 2f..5f,
                                    inputUnit = "次",
                                    insideMargin = PaddingValues(top = 14.dp, bottom = 8.dp),
                                )
                            }
                        }
                    }
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

}

private fun toast(context: Context, msg: String) {
    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
}

private fun firstLine(out: String): String =
    out.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "操作失败"
