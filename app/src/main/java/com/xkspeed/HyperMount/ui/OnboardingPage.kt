package com.xkspeed.HyperMount.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import com.xkspeed.HyperMount.core.Module
import com.xkspeed.HyperMount.core.ModuleState
import com.xkspeed.HyperMount.core.Root
import com.xkspeed.HyperMount.i18n.t
import com.xkspeed.HyperMount.ui.component.GlowBackdrop
import com.xkspeed.HyperMount.ui.util.stableNavigationBarsPadding
import com.xkspeed.HyperMount.ui.util.stableStatusBarsPadding
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** 引导总步数：欢迎 / 须知 / 环境 / 音量键 / 完成 */
private const val ONBOARD_STEPS = 5

/**
 * 引导页版本
 *
 * 引导内容一改就把这个数 +1（加一步、换文案、加权限引导都算），
 * App 里记着用户走完时的版本，对不上就再走一遍 —— 不跟着 App 版本号走，
 * 普通发版不会平白把老用户拉回引导页，只有真的改了引导才打扰一次。
 * 1 = 环境页加自启动权限引导这一版。
 * 2 = 整套界面换成 OOBE 风格（深色欢迎页 + 圆形下一步）。
 * 3 = 按 HyperCeiler 版式重做（光晕着色器、欢迎页只留 logo 与圆钮）。
 */
const val ONBOARD_VERSION = 3

/** 音量键单步状态 */
private sealed interface KeyState {
    data object Idle : KeyState
    data object Waiting : KeyState
    data object Passed : KeyState
    data object Failed : KeyState
}

/** 三步环境检查的结果 */
private data class EnvResult(
    val root: Boolean = false,
    val moduleVersion: String = "",
    val moduleAuthor: String = "",
) {
    /** 只认 XKspeed 的模块：别家挂载的没有这套 webctl.sh / check.sh 调不动 */
    val moduleOk: Boolean
        get() = moduleVersion.isNotBlank() && moduleAuthor.equals("XKspeed", ignoreCase = true)
}

/** 欢迎页的底色，比主题深色再深一档 光斑才有地方浮 */
private val InkBlack = Color(0xFF08080A)

/** 深色页的主文字色 */
private val OnInk = Color(0xFFF5F5F7)

/** cubicOut：原作 Folme 的 FolmeEase.cubicOut，Compose 没有内置这个曲线 */
private val CubicOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/**
 * 首次启动引导
 *
 * 视觉照 HyperCeiler 的 provision（MIUI 首次开机向导）来：
 * 欢迎页黑底 + 呼吸光斑 + 居中大图标，点圆形箭头进入后面几页；
 * 后面几页回到浅色 surface、大标题、无卡片的大留白。
 * 那套是 OpenGL 渲染 + MIUI 私有库（fan.core / fan.provision），普通 App 链接不到，
 * 这里用 Compose 复刻同样的节奏与参数：logo 0.5→0.95→1 两段缓动、
 * 按钮延迟 1.2 秒才淡入、光斑 9 秒一个来回。
 *
 * 流程本身没动：环境检查、音量键校验、3 秒倒计时、返回拦截全部保留。
 * 过程中唯一落盘的是音量键校验结果（VOLUME_CHECK_ASSESS=1）；
 * 引导完成状态（onboarded + ONBOARD_VERSION）只在最后一页点「完成」时由
 * [onDone] 统一下发，中途退出不会被记成走完。
 */
@Composable
fun OnboardingPage(
    onDone: () -> Unit,
    /** 直接从音量键这一步开始（系统版本变了要重验时用） */
    startAtVolume: Boolean = false,
    /** 强制校验：忽略已校验标记 作废模块侧结果后重按一遍 */
    forceVolume: Boolean = false,
    /** 从调试页回看时给个退出口；首次启动传 null，第一页按返回不做事 */
    onCancel: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    var step by remember { mutableIntStateOf(if (startAtVolume) 3 else 0) }
    var agreed by remember { mutableStateOf(false) }

    // 第 2 页强制停留 3 秒：进来从 3 倒数 归零前「同意并继续」不可点
    // 回退再进来重新计时 免得拿后退绕过
    var agreeLeft by remember { mutableIntStateOf(3) }
    LaunchedEffect(step) {
        agreeLeft = 3
        if (step != 1) return@LaunchedEffect
        while (agreeLeft > 0) {
            delay(1000)
            agreeLeft--
        }
    }

    // 环境检查
    var envChecking by remember { mutableStateOf(false) }
    var env by remember { mutableStateOf(EnvResult()) }
    var envRetry by remember { mutableIntStateOf(0) }

    // 音量键
    var upState by remember { mutableStateOf<KeyState>(KeyState.Idle) }
    var downState by remember { mutableStateOf<KeyState>(KeyState.Idle) }
    var volHint by remember { mutableStateOf("") }
    var volRetry by remember { mutableIntStateOf(0) }
    var volSkip by remember { mutableStateOf(false) }
    var volAlready by remember { mutableStateOf(false) }

    val appIcon = remember {
        runCatching {
            context.packageManager
                .getApplicationIcon(context.packageName)
                .toBitmap(192, 192)
                .asImageBitmap()
        }.getOrNull()
    }

    // 进环境页就检查一遍 root / 模块
    LaunchedEffect(step, envRetry) {
        if (step != 2) return@LaunchedEffect
        envChecking = true
        val root = runCatching { Root.available() }.getOrDefault(false)
        val st: ModuleState? = if (root) runCatching { Module.readState() }.getOrNull() else null
        env = EnvResult(
            root = root,
            moduleVersion = st?.version.orEmpty(),
            moduleAuthor = st?.moduleAuthor.orEmpty(),
        )
        // 之前校验过就不用再按一遍 直接当作通过（版本变了强制重验时不认旧结果）
        if (st?.volumeAssessed == true && !forceVolume) {
            volAlready = true
            upState = KeyState.Passed
            downState = KeyState.Passed
            volHint = t("之前已经校验过 直接继续就行", "Verified before — you can continue directly")
        }
        envChecking = false
    }

    // 进音量页自动开始：先等音量+ 再等音量−
    LaunchedEffect(step, volRetry) {
        if (step != 3) return@LaunchedEffect
        if (volAlready || volSkip) return@LaunchedEffect
        // 环境检查可能还没跑完（用户点得快）这里再确认一次 root 别误报
        val rootNow = env.root || runCatching { Root.available() }.getOrDefault(false)
        if (!rootNow) {
            upState = KeyState.Failed
            downState = KeyState.Failed
            volHint = t("没有 ROOT 权限 读不到按键设备 先去授权再来", "No ROOT access — key devices can't be read. Grant it first and come back")
            return@LaunchedEffect
        }

        // 系统版本对不上：先把模块里的校验标记作废 否则救砖会拿旧设备号
        if (forceVolume) {
            runCatching {
                Module.setConf("VOLUME_CHECK_ASSESS", "0")
                Module.setConf("VOL_UP_DEV", "")
                Module.setConf("VOL_DOWN_DEV", "")
            }
        }

        upState = KeyState.Waiting
        downState = KeyState.Idle
        volHint = t("请按一次【音量 +】", "Press [Volume +] once")
        val up = runCatching { Module.check("up") }.getOrDefault("").trim()
        val upLine = up.lineSequence().firstOrNull { it.startsWith("OK") }
        if (upLine == null) {
            upState = KeyState.Failed
            volHint = t("没读到音量 + 确认键没坏 点下面的重试再来一次", "Volume + wasn't read — make sure the key isn't broken, then retry below")
            return@LaunchedEffect
        }
        upState = KeyState.Passed
        val upDev = upLine.removePrefix("OK").trim()

        downState = KeyState.Waiting
        volHint = t("请按一次【音量 −】", "Press [Volume −] once")
        val down = runCatching { Module.check("down") }.getOrDefault("").trim()
        val downLine = down.lineSequence().firstOrNull { it.startsWith("OK") }
        if (downLine == null) {
            downState = KeyState.Failed
            volHint = t("音量 + 通过了 音量 − 没读到 再试一次", "Volume + passed, but Volume − wasn't read — try again")
            return@LaunchedEffect
        }
        downState = KeyState.Passed
        val downDev = downLine.removePrefix("OK").trim()

        // 两个都过才写配置 记下实测设备 救砖时不用再猜
        val save = runCatching { Module.saveVolumeCheck(upDev, downDev) }.getOrDefault("")
        if (save.contains("OK")) {
            // 记下这次校验时的系统版本 下次系统升级才会再拉一次
            UiConfigState.markVolumeChecked(context)
            volHint = t("校验通过 已写入模块配置", "Check passed — saved to the module config")
        } else {
            volHint = t("按键能读到 但写入配置失败 去设置里再校验一次", "Keys are readable, but saving failed — run the check again in Settings")
        }
    }

    val volDone = upState is KeyState.Passed && downState is KeyState.Passed
    // 环境这两项缺一不可：没有 ROOT 或不是 XKspeed 的模块 后面所有操作都会失败
    val envReady = env.root && env.moduleOk
    val canNext = when (step) {
        1 -> agreed && agreeLeft == 0
        2 -> !envChecking && envReady
        else -> true
    }

    // 深色页 / 浅色页切换：状态栏图标得跟着反色，否则黑底上是黑图标
    val surface = MiuixTheme.colorScheme.surface
    val darkPage = step == 0 || surface.luminance() < 0.5f
    val view = LocalView.current
    DisposableEffect(darkPage, view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = !darkPage
        controller?.isAppearanceLightNavigationBars = !darkPage
        onDispose { }
    }

    fun next() {
        // 环境没过 后面几步都不该放行
        if (step == 2 && (!envReady || envChecking)) return
        if (step == 3 && !volDone) volSkip = true
        if (step < ONBOARD_STEPS - 1) step++
    }

    // 只能一步步往回退 退到第一步就不再拦 由系统退出 App
    BackHandler(enabled = step > 0 || onCancel != null) {
        if (step == 0) {
            // 只剩回看入口这一种情况会走到这里：直接退回调试页
            onCancel?.invoke()
        } else {
            if (step == 3 && !volDone) volSkip = true
            step--
        }
    }

    // 引导页固定浅色：原作就是这么做的（后几页纯白底），
    // 跟随系统深色的话 title / 正文在深色 surface 上会颠倒
    val pageColors = remember { lightColorScheme() }
    MiuixTheme(colors = pageColors) {
        val bg by animateColorAsState(
            targetValue = if (step == 0) InkBlack else MiuixTheme.colorScheme.surface,
            animationSpec = tween(500),
            label = "onboardBg",
        )

        Box(Modifier.fillMaxSize().background(bg)) {
            // 光斑只在欢迎页露头，切走时淡出
            AnimatedVisibility(
                visible = step == 0,
                enter = fadeIn(tween(700)),
                exit = fadeOut(tween(400)),
                modifier = Modifier.fillMaxSize(),
            ) {
                GlowBackdrop()
            }

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(420, easing = FastOutSlowInEasing)) { it / 5 * dir } +
                        fadeIn(tween(420))) togetherWith
                        (slideOutHorizontally(tween(420)) { -it / 5 * dir } + fadeOut(tween(300)))
                },
                modifier = Modifier.fillMaxSize(),
                label = "onboarding",
            ) { index ->
                when (index) {
                    0 -> WelcomeStep(appIcon) { next() }
                    1 -> NoticeStep()
                    2 -> EnvStep(
                        env = env,
                        checking = envChecking,
                        onRetry = { envRetry++ },
                        onOpenAutostart = {
                            if (openAutostart(context)) {
                                UiConfigState.autostartPrompted = true
                                UiConfigState.save(context)
                            } else {
                                Toast.makeText(
                                    context,
                                    t("没找到自启动管理页 去手机管家里手动允许", "Couldn't find the autostart page — allow it manually in your phone manager"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                    3 -> VolumeStep(upState, downState, volHint, canRetry = !volAlready) {
                        volSkip = false
                        volRetry++
                    }
                    else -> DoneStep()
                }
            }

            // 欢迎页的按钮按 30/40/20 的比例落位（在 WelcomeStep 内部），
            // 其余页统一用原作后几页那种 50dp 胶囊贴底
            if (step > 0) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .stableNavigationBarsPadding()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 44.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (step == 1) {
                        // 整行仍可点，但不带 indication ——
                        // 按下高亮只落在勾选框上，不铺满整行
                        val rowInteraction = remember { MutableInteractionSource() }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(
                                    interactionSource = rowInteraction,
                                    indication = null,
                                ) { agreed = !agreed }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                state = if (agreed) ToggleableState.On else ToggleableState.Off,
                                onClick = { agreed = !agreed },
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                t("我已阅读并了解以上内容", "I have read and understood the above"),
                                style = MiuixTheme.textStyles.main,
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (agreeLeft > 0) {
                                Text(
                                    t("$agreeLeft 秒", "$agreeLeft s"),
                                    style = MiuixTheme.textStyles.subtitle,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }

                    // 胶囊里放不下说明，状态写它上面
                    val tip = when {
                        step == 2 && envChecking -> t("正在检查环境…", "Checking the environment…")
                        step == 2 && !envReady -> t("环境检查通过后才能继续", "Both checks must pass to continue")
                        step == 3 && !volDone -> t("按一次音量 + 和音量 −", "Press Volume + then Volume −")
                        else -> ""
                    }
                    if (tip.isNotBlank()) {
                        Text(
                            tip,
                            style = MiuixTheme.textStyles.subtitle,
                            color = if (step == 2 && !envReady && !envChecking) MiuixTheme.colorScheme.error
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    NextPillButton(
                        label = when (step) {
                            1 -> t("同意并继续", "Agree and continue")
                            4 -> t("完成", "Done")
                            else -> t("继续", "Continue")
                        },
                        enabled = canNext,
                        // 最后一步的「完成」直接落盘引导完成状态（含 ONBOARD_VERSION），
                        // 中途任何一步退出去都不会被记成「走完了」
                        onClick = {
                            if (step == ONBOARD_STEPS - 1) onDone() else next()
                        },
                    )

                    // 音量页跳过：次要动作给文字入口
                    if (step == 3 && !volDone) {
                        Spacer(Modifier.height(6.dp))
                        TextAction(t("跳过校验", "Skip check")) {
                            volSkip = true
                            next()
                        }
                    }
                }
            }
        }
    }
}

// ---------------- 底部按钮 ----------------

/**
 * 欢迎页的圆钮
 *
 * 原作 startup 的标准配置：70dp 纯白圆、深色箭头，容器底部留 4.5dp。
 * 入场比 logo 晚 1.34 秒，从 0.9 缩到 1（cubicOut 450ms）。
 */
@Composable
private fun WelcomeNextButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "nextPress",
    )

    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(1340)
        appear.animateTo(1f, tween(450, easing = CubicOut))
    }

    Box(
        Modifier
            .graphicsLayer {
                val s = pressScale * (0.9f + 0.1f * appear.value)
                scaleX = s
                scaleY = s
                alpha = appear.value
            }
            .size(70.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(29.dp, 20.dp)) {
            val w = size.width
            val h = size.height
            val stroke = Stroke(width = h * 0.19f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val shaft = Path().apply {
                moveTo(w * 0.07f, h * 0.5f)
                lineTo(w * 0.93f, h * 0.5f)
                moveTo(w * 0.68f, h * 0.13f)
                lineTo(w * 0.93f, h * 0.5f)
                lineTo(w * 0.68f, h * 0.87f)
            }
            drawPath(shaft, color = Color(0xFF1A1A1A), style = stroke)
        }
    }
}

/**
 * 后几页的下一步
 *
 * 和原作一样是 50dp 高的蓝色胶囊、最宽 336dp、距底 44dp。
 */
@Composable
private fun NextPillButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "pillPress",
    )

    Box(
        Modifier
            .widthIn(max = 336.dp)
            .fillMaxWidth()
            .height(50.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.35f
            }
            .clip(RoundedCornerShape(25.dp))
            .background(MiuixTheme.colorScheme.primary)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onPrimary,
        )
    }
}

/** 次要动作的文字按钮 */
@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}

// ---------------- 第 1 页：欢迎（深色） ----------------

/**
 * 欢迎页：只有 logo 和圆钮，和原作一样不放任何说明文字
 *
 * 纵向按原作的 30 : 90dp : 40 : 20 分配（图标固定 90dp，其余三段按比例分剩余高度），
 * 所以按钮落在屏幕下方约 18% 的位置，而不是贴着底边。
 */
@Composable
private fun WelcomeStep(icon: androidx.compose.ui.graphics.ImageBitmap?, onNext: () -> Unit) {
    // logo：0.5 → 0.95（sinOut 440ms）→ 1（cubicOut 700ms），照原作两段缓动
    val logoScale = remember { Animatable(0.5f) }
    val logoAlpha = remember { Animatable(0f) }
    // 文字 logo 比图标晚一拍淡入
    val markAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        logoAlpha.animateTo(1f, tween(420, easing = LinearOutSlowInEasing))
        logoScale.animateTo(0.95f, tween(440, easing = FastOutSlowInEasing))
        logoScale.animateTo(1f, tween(700, easing = CubicOut))
    }
    LaunchedEffect(Unit) {
        delay(120)
        markAlpha.animateTo(1f, tween(420))
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(30f))

        Box(
            Modifier.fillMaxWidth().height(90.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .graphicsLayer {
                        scaleX = logoScale.value
                        scaleY = logoScale.value
                        alpha = logoAlpha.value
                    }
                    .size(90.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier.size(90.dp).clip(RoundedCornerShape(22.dp)),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(
                        imageVector = MiuixIcons.Layers,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = OnInk,
                    )
                }
            }
        }

        Box(Modifier.weight(40f).fillMaxWidth()) {
            Text(
                "HyperMount",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp)
                    .graphicsLayer { alpha = markAlpha.value },
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                color = OnInk,
            )

            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.5.dp)) {
                WelcomeNextButton(onClick = onNext)
            }
        }

        Spacer(Modifier.weight(20f))
    }
}

// ---------------- 第 2 页：使用须知 ----------------

@Composable
private fun NoticeStep() {
    StepScaffold(title = t("使用之前", "Before you start")) {
        NoticeItem(
            t("需要 ROOT 与模块", "ROOT and the module are required"),
            t(
                "所有操作都走 root shell 调用 systemui_mount 的 webctl.sh 和 check.sh " +
                    "没装模块时只能看不能救 ",
                "Everything runs through a root shell calling systemui_mount's webctl.sh and check.sh — " +
                    "without the module you can look but not rescue ",
            ),
        )
        NoticeItem(
            t("救砖包必须能用", "The rescue build must actually work"),
            t(
                "换回去的包得是能正常启动的系统界面 如果救砖包本身也是坏的 换回去照样黑屏 ",
                "The package you roll back to must boot normally — a broken rescue build still means a black screen ",
            ),
        )
        NoticeItem(
            t("出问题自己负责", "You are responsible for what happens"),
            t(
                "改系统界面有风险 刷机、模块冲突导致的后果需要你自己承担 ",
                "Swapping the SystemUI carries risks — consequences of flashing or module conflicts are on you ",
            ),
        )
    }
}

/** 浅色页的统一骨架：大标题 + 大留白正文 */
@Composable
private fun StepScaffold(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .stableStatusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp)
            .padding(bottom = 130.dp),
    ) {
        Spacer(Modifier.height(26.dp))
        // 原作标题 32sp，颜色是 miui 的 on_surface（浅色下接近纯黑）
        Text(
            title,
            fontSize = 32.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(26.dp))
        content()
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun NoticeItem(title: String, desc: String) {
    Column(Modifier.padding(bottom = 24.dp)) {
        Text(title, style = MiuixTheme.textStyles.main, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(
            desc,
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

// ---------------- 第 3 页：环境检查 ----------------

/**
 * 打开系统的自启动管理页
 *
 * HyperOS / MIUI 直接点名 AutoStartManagementActivity，其他家给几个常见入口；
 * 都不认就退到应用详情页 至少用户能在「权限」里自己找。
 * 注意不能用 resolveActivity 预判：Android 11 起没在 queries 里声明的包看不见，
 * 直接 startActivity 再捕异常更准。
 */
private fun openAutostart(context: Context): Boolean {
    val pages = listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
    )
    for (page in pages) {
        val ok = runCatching {
            context.startActivity(
                Intent().setComponent(page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (ok) return true
    }
    return runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
}

@Composable
private fun EnvStep(
    env: EnvResult,
    checking: Boolean,
    onRetry: () -> Unit,
    onOpenAutostart: () -> Unit,
) {
    StepScaffold(title = t("环境检查", "Environment check")) {
        StatusRow(
            label = t("ROOT 权限", "ROOT access"),
            value = when {
                checking -> t("检测中…", "Checking…")
                env.root -> t("已授权", "Granted")
                else -> t("未授权", "Not granted")
            },
            ok = env.root,
            checking = checking,
        )
        Divider()
        StatusRow(
            label = t("救砖模块", "Rescue module"),
            value = when {
                checking -> t("检测中…", "Checking…")
                env.moduleVersion.isBlank() -> t("未安装", "Not installed")
                !env.moduleOk -> t("不是 XKspeed 版", "Not an XKspeed build")
                else -> "v${env.moduleVersion} · XKspeed"
            },
            ok = env.moduleOk,
            checking = checking,
        )

        Spacer(Modifier.height(10.dp))
        TextAction(if (checking) t("正在检测…", "Checking…") else t("重新检测", "Check again")) {
            if (!checking) onRetry()
        }

        // 自启动：守护要在后台常驻 光有前台服务不够 系统照样清
        Spacer(Modifier.height(18.dp))
        Card(Modifier.fillMaxWidth()) {
            ArrowPreference(title = t("自启动", "Autostart"), onClick = onOpenAutostart)
        }

        if (!checking && !(env.root && env.moduleOk)) {
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MiuixTheme.colorScheme.error.copy(alpha = 0.08f))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    t("两项都通过才能继续", "Both checks must pass to continue"),
                    style = MiuixTheme.textStyles.main,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.error,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    when {
                        !env.root -> t("先在 ROOT 管理器里给 HyperMount 授权 然后点上面的「重新检测」 ", "Grant HyperMount in your ROOT manager first, then tap \"Check again\" above ")
                        env.moduleVersion.isBlank() -> t("还没装救砖模块 去 Magisk / KernelSU 里刷入模块 重启后再回来检测 ", "The rescue module isn't installed yet — flash it in Magisk / KernelSU, reboot, then check again ")
                        else -> t("当前模块不是 XKspeed 版 它的脚本对不上 请换成官方发布的那份 ", "The current module isn't an XKspeed build and its scripts don't match — use the official release ")
                    },
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            t("模块作者必须是 XKspeed 用的才是这套脚本 别家挂载的调不了 sh ", "The module author must be XKspeed — only that one carries these scripts; modules mounted by others can't be driven "),
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    )
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean, checking: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MiuixTheme.textStyles.main, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MiuixTheme.textStyles.subtitle,
            color = when {
                checking -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                ok -> MiuixTheme.colorScheme.primary
                else -> MiuixTheme.colorScheme.error
            },
        )
    }
}

// ---------------- 第 4 页：音量键校验 ----------------

@Composable
private fun VolumeStep(
    up: KeyState,
    down: KeyState,
    hint: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    StepScaffold(title = t("音量键校验", "Volume key check")) {
        KeyRow(t("音量 +", "Volume +"), up)
        Divider()
        KeyRow(t("音量 −", "Volume −"), down)

        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (up is KeyState.Waiting || down is KeyState.Waiting) {
                PulsingDot()
                Spacer(Modifier.width(9.dp))
            }
            Text(
                hint.ifBlank { t("准备中…", "Getting ready…") },
                style = MiuixTheme.textStyles.subtitle,
                color = when {
                    up is KeyState.Failed || down is KeyState.Failed ->
                        MiuixTheme.colorScheme.error
                    up is KeyState.Passed && down is KeyState.Passed ->
                        MiuixTheme.colorScheme.primary
                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
        }

        if (canRetry && up !is KeyState.Waiting && down !is KeyState.Waiting) {
            Spacer(Modifier.height(8.dp))
            TextAction(t("重新校验", "Check again"), onRetry)
        }
    }
}

@Composable
private fun KeyRow(label: String, state: KeyState) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MiuixTheme.textStyles.main, modifier = Modifier.weight(1f))
        Text(
            when (state) {
                KeyState.Idle -> t("等待", "Waiting")
                KeyState.Waiting -> t("请按键…", "Press a key…")
                KeyState.Passed -> t("已识别", "Detected")
                KeyState.Failed -> t("没读到", "Not read")
            },
            style = MiuixTheme.textStyles.subtitle,
            color = when (state) {
                KeyState.Passed -> MiuixTheme.colorScheme.primary
                KeyState.Failed -> MiuixTheme.colorScheme.error
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
        )
    }
}

/** 等待按键时的小呼吸点 让「正在听」这件事有反馈 */
@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.primary.copy(alpha = alpha))
    )
}

// ---------------- 第 5 页：完成 ----------------

@Composable
private fun DoneStep() {
    val context = LocalContext.current
    var autoCheck by remember { mutableStateOf(UiConfigState.autoCheckUpdate) }

    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, tween(1050, easing = FastOutSlowInEasing))
    }

    Column(
        Modifier
            .fillMaxSize()
            .stableStatusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp)
            .padding(bottom = 130.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))
        Box(
            Modifier
                .graphicsLayer {
                    translationY = (1f - enter.value) * 24.dp.toPx()
                    alpha = enter.value
                }
                .size(88.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            val checkColor = MiuixTheme.colorScheme.primary
            Canvas(Modifier.size(42.dp)) {
                val w = size.width
                val h = size.height
                val stroke = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val path = Path().apply {
                    moveTo(w * 0.18f, h * 0.52f)
                    lineTo(w * 0.42f, h * 0.75f)
                    lineTo(w * 0.84f, h * 0.27f)
                }
                drawPath(path, color = checkColor, style = stroke)
            }
        }
        Spacer(Modifier.height(26.dp))
        Text(
            t("设置完毕", "All set"),
            fontSize = 32.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(40.dp))
        Card(Modifier.fillMaxWidth()) {
            SwitchPreference(
                checked = autoCheck,
                onCheckedChange = {
                    autoCheck = it
                    UiConfigState.autoCheckUpdate = it
                    UiConfigState.save(context)
                },
                title = t("自动检查更新", "Auto check"),
                summary = t("启动时看一眼 GitHub 上有没有新版本", "Look for new releases on GitHub at launch"),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
