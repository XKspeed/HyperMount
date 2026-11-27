package com.xkspeed.HyperMount.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.xkspeed.HyperMount.R
import com.xkspeed.HyperMount.core.Module
import com.xkspeed.HyperMount.core.ModuleState
import com.xkspeed.HyperMount.core.Root
import com.xkspeed.HyperMount.i18n.t
import com.xkspeed.HyperMount.ui.component.effect.BgEffectBackground
import com.xkspeed.HyperMount.ui.util.isInDarkTheme
import com.xkspeed.HyperMount.ui.util.rememberBlurBackdrop
import com.xkspeed.HyperMount.ui.util.stableNavigationBarsPadding
import com.xkspeed.HyperMount.ui.util.stableStatusBarsPadding
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xkspeed.HyperMount.ui.component.oobe.OobeTuning
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
 * 4 = 欢迎页换成浅色柔光底，图标/字标/圆钮改单色采样，加圆形放大转场。
 */
const val ONBOARD_VERSION = 4

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

/** 欢迎页的纸底色：背景图淡出/没铺满时露出来的颜色，取自图的中心调 */
private val WelcomePaper = Color(0xFFF3E9E7)

/** 浅色页上的主图形色；采样不可用时字标/图标/圆钮退回这个色 */
private val OnPaper = Color(0xFF1C1C21)

/** cubicOut：原作 Folme 的 FolmeEase.cubicOut，Compose 没有内置这个曲线 */
private val CubicOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/** sinOut：原作 Folme 的 FolmeEase.sinOut，sin(t · π/2) */
private val SinOut = Easing { sin(it * PI / 2).toFloat() }

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
fun OobePage(
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

    // 首页光晕要能被字标采样：这层录进 backdrop，字标再用 textureBlur 取
    val backdrop = rememberBlurBackdrop()
    // 首页锁成反色：系统深色时铺浅色流光，系统浅色时铺深色流光
    // 点圆钮展开第二页时颜色正好翻回来，转场不用另做一套
    val homeDark = !isInDarkTheme()
    // 图标 / 字标 / 圆钮共用同一份流光染色（关于页 HyperMount 那套）。
    // 首页底子是反色的，深底选提亮的一组，浅底选压暗的一组。
    // 色值实时取 OobeTuning，调参页改完进 OOBE 就是新的；
    // key 用当前三条色的快照，色没变就不重建 BlurColors（否则每帧都重建着色器）
    val nameBlend = remember(homeDark, OobeTuning.colors(homeDark).toList()) {
        brandNameBlend(onDark = homeDark)
    }

    // 深色页 / 浅色页切换：状态栏图标得跟着反色
    // 首页是反色流光：系统深色时首页反而偏浅，这时状态栏要放深色图标
    val surface = MiuixTheme.colorScheme.surface
    val darkPage = if (step == 0) homeDark else surface.luminance() < 0.5f
    val view = LocalView.current
    DisposableEffect(darkPage, view, step) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = !darkPage
        controller?.isAppearanceLightNavigationBars = !darkPage
        onDispose { }
    }

    // 欢迎页点圆钮：第二页从圆钮位置长成整屏（MIUI scale-up 转场的 Compose 版）
    var reveal by remember { mutableStateOf<RevealSpec?>(null) }
    val revealProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // 圆钮在窗口里的位置：返回时要从同一点缩回去
    var buttonSpec by remember { mutableStateOf<RevealSpec?>(null) }
    // 首页的入场动画只在第一次进来时放；从第二页退回来时直接停在终态，不再重播
    var introDone by remember { mutableStateOf(false) }
    // 圆形转场进行中：这半秒里别让第二次点击/返回插进来
    var transitionBusy by remember { mutableStateOf(false) }

    fun startReveal(c: Offset, r: Float) {
        if (transitionBusy) return
        transitionBusy = true
        scope.launch {
            revealProgress.snapTo(0f)
            val spec = RevealSpec(c, r)
            buttonSpec = spec
            introDone = true
            reveal = spec
            revealProgress.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
            step = 1
            // 等这一帧真的把第二页画出来再撤遮罩：
            // 同帧撤的话，只要 AnimatedContent 慢半帧，就会露出一次欢迎页
            withFrameNanos { }
            reveal = null
            transitionBusy = false
        }
    }

    /**
     * 第二页退回第一页：进入动画的反向播放
     *
     * 圆圈里装的还是第二页，半径从整屏缩回圆钮，露出底下已经摆好的欢迎页。
     * 不这么做的话返回走的是 AnimatedContent 的横向滑动，和进场的圆形展开对不上。
     */
    fun startCollapse(): Boolean {
        val spec = buttonSpec ?: return false
        if (transitionBusy) return false
        transitionBusy = true
        scope.launch {
            revealProgress.snapTo(1f)
            // 先把底下的欢迎页换出来（这个方向的 AnimatedContent 是 None，不滑动），
            // 同一帧里撑起整屏的圆把它盖住，所以看不到切换
            step = 0
            reveal = spec
            withFrameNanos { }
            revealProgress.animateTo(0f, tween(420, easing = FastOutLinearInEasing))
            reveal = null
            transitionBusy = false
        }
        return true
    }

    fun next() {
        // 环境没过 后面几步都不该放行
        if (step == 2 && (!envReady || envChecking)) return
        if (step == 3 && !volDone) volSkip = true
        if (step < ONBOARD_STEPS - 1) step++
    }

    // 只能一步步往回退 退到第一步就不再拦 由系统退出 App
    BackHandler(enabled = step > 0 || onCancel != null) {
        // 转场的半秒里别让返回把页面拽回去
        if (transitionBusy) return@BackHandler
        if (step == 0) {
            // 只剩回看入口这一种情况会走到这里：直接退回调试页
            onCancel?.invoke()
        } else if (step == 1 && startCollapse()) {
            // 1 → 0 走圆形收缩，和进场的圆形展开对称
        } else {
            if (step == 3 && !volDone) volSkip = true
            step--
        }
    }

    val bg by animateColorAsState(
        // 首页底色要跟反色流光同调：系统浅色时首页是深流光，垫色不能再是浅粉，
        // 不然背景层淡出/退场的那一下会闪一层粉
        targetValue = if (step == 0) {
            if (homeDark) Color(0xFF121216) else WelcomePaper
        } else {
            MiuixTheme.colorScheme.surface
        },
        animationSpec = tween(500),
        label = "onboardBg",
    )

    Box(Modifier.fillMaxSize().background(bg)) {
        // 欢迎页的柔光底：原作是「黑底 + 光晕 + 半透明浅色壁纸」，壁纸不透明度很高，
        // 最后看到的就是那张浅粉紫渐变，所以这里直接铺图，光晕不往上叠。
        AnimatedVisibility(
            visible = step == 0,
            // 从第二页退回来时，整块底图要让圆形收缩的第一帧就到位：
            // 那会儿圆还盖满全屏，看不出来；一旦开始淡入，缩小时就会露出半透明的底
            enter = EnterTransition.None,
            exit = fadeOut(tween(400)),
            modifier = Modifier.fillMaxSize(),
        ) {
            WelcomeBackdrop(backdrop)
        }

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                // 0 ↔ 1 是圆形放大/收缩自己走完的，这里必须"立刻"换页：
                // 用 tween(0) 仍会经过一帧动画系统，遮罩撤掉时那一帧露出来的还是原来那页
                if ((initialState == 0 && targetState == 1) ||
                    (initialState == 1 && targetState == 0)
                ) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(420, easing = FastOutSlowInEasing)) { it / 5 * dir } +
                        fadeIn(tween(420))) togetherWith
                        (slideOutHorizontally(tween(420)) { -it / 5 * dir } + fadeOut(tween(300)))
                }
            },
            modifier = Modifier.fillMaxSize(),
            label = "onboarding",
        ) { index ->
            when (index) {
                0 -> WelcomeStep(backdrop, nameBlend, homeDark, animateIn = !introDone) { c, r ->
                    startReveal(c, r)
                }
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

        // 圆形放大层：从圆钮长成整屏，把欢迎页盖住、露出第二页
        // 半径在 draw 阶段读，只重绘不重组 —— 否则这 520ms 里第二页会被反复重组
        reveal?.let { spec ->
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        val target = hypot(size.width, size.height)
                        val r = spec.radius + (target - spec.radius) * revealProgress.value
                        clipPath(Path().apply { addOval(Rect(spec.center, r)) }) {
                            drawRect(surface)
                            this@drawWithContent.drawContent()
                        }
                    },
            ) {
                NoticeStep()
            }
        }

        // 欢迎页的按钮按 30/40/20 的比例落位（在 WelcomeStep 内部），
        // 其余页统一用原作后几页那种 50dp 胶囊贴底。
        // 圆形转场刚结束时先淡入，别跟着那一下硬切出来
        AnimatedVisibility(
            visible = step > 0,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
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

// ---------------- 底部按钮 ----------------

/**
 * 欢迎页的圆钮
 *
 * 原作 startup 的标准配置：70dp 纯白圆、深色箭头，容器底部留 4.5dp。
 * 入场比 logo 晚 1.34 秒，从 0.9 缩到 1（cubicOut 450ms）。
 */
@Composable
private fun WelcomeNextButton(
    backdrop: LayerBackdrop?,
    onDark: Boolean,
    // 从第二页退回来时按钮已经在位，不再重播「延迟 1.34 秒淡入」
    animateIn: Boolean,
    onClick: (Offset, Float) -> Unit,
) {
    // 圆钮的混色和字标同一条：用关于页 HyperMount 那套（ColorDodge/ColorBurn + 品牌色），
    // 采样出来是一颗带彩边的圆，跟着背景流光变，不再固定一颗灰圆。
    // 深底那组是提亮的，圆会是浅色，箭头必须翻成深色才看得见。
    val btnBlend = remember(onDark, OobeTuning.colors(onDark).toList()) {
        brandNameBlend(onDark = onDark)
    }
    val darkArrow = onDark

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "nextPress",
    )

    val appear = remember { Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!animateIn) return@LaunchedEffect
        delay(1340)
        appear.animateTo(1f, tween(450, easing = CubicOut))
    }

    // 转场要从这个圆长出去，记一下它在窗口里的圆心和半径
    var center by remember { mutableStateOf(Offset.Zero) }
    var radius by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .onGloballyPositioned { coords ->
                center = coords.positionInRoot() + Offset(coords.size.width / 2f, coords.size.height / 2f)
                radius = coords.size.width / 2f
            }
            .graphicsLayer {
                val s = pressScale * (0.9f + 0.1f * appear.value)
                scaleX = s
                scaleY = s
                alpha = appear.value
            }
            .size(70.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onClick(center, radius) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 模糊必须单独一层：DstIn 只把「内容」当遮罩、内容本身不再绘制，
        // 白圆放这层里当遮罩；箭头必须留在外层当兄弟节点，否则会被一起吃掉
        Box(
            Modifier
                .matchParentSize()
                .then(
                    if (backdrop != null) {
                        Modifier.textureBlur(
                            backdrop = backdrop,
                            shape = CircleShape,
                            blurRadius = 150f,
                            noiseCoefficient = BlurDefaults.NoiseCoefficient,
                            colors = BlurDefaults.blurColors(blendColors = btnBlend),
                            contentBlendMode = BlendMode.DstIn,
                        )
                    } else {
                        Modifier
                    },
                )
                .background(if (backdrop != null) Color.White else OnPaper),
        )
        // 原作的箭头是独立图层、不参与采样（描边版会显得太粗太圆）
        Image(
            painter = painterResource(R.drawable.ic_welcome_arrow),
            contentDescription = null,
            colorFilter = if (darkArrow) ColorFilter.tint(OnPaper) else null,
            modifier = Modifier.size(29.dp, 20.dp),
        )
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

// ---------------- 第 1 页：欢迎 ----------------

/**
 * 欢迎页的流光底
 *
 * 换成关于页同款 [BgEffectBackground]（AGSL 流光），并**锁成反色**：
 * 系统深色时铺浅色流光、系统浅色时铺深色流光。
 * 点圆钮展开第二页时颜色正好翻回来，转场本身就是一次「反色复原」。
 * 这层要录进 [backdrop]，字标、图标、圆钮才有东西可以采。
 */
@Composable
private fun WelcomeBackdrop(backdrop: LayerBackdrop?) {
    // 没有 backdrop 说明这台设备跑不了 AGSL（API < 33 或 shader 不支持），
    // 退回原来那张静态柔光图，不至于只剩一块纯色
    if (backdrop == null) {
        Box(Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(R.drawable.welcome_bg),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }
    BgEffectBackground(
        dynamicBackground = true,
        modifier = Modifier.fillMaxSize(),
        isFullSize = true,
        invertTheme = true,
        bgModifier = Modifier.layerBackdrop(backdrop),
        alpha = { 1f },
    ) { }
}

/**
 * 欢迎页图标
 *
 * 照原作的做法：不是把线条画在透明底上，而是「实心圆角方块挖掉图形」。
 * 采样只认 alpha（[BlendMode.DstIn]），方块和图形必须一实一空才分得开 ——
 * 原作那个 28dp 的 logo 之所以是一整块白、里面镂空，就是为了这个。
 * 图形沿用应用图标的 270° 环 + 缺口圆点，按 90dp 的方块重排比例。
 */
@Composable
private fun WelcomeLogo(
    backdrop: LayerBackdrop?,
    nameBlend: List<BlendColorEntry>,
    onDark: Boolean,
    logoScale: Float,
    logoAlpha: Float,
    // 图标中心的窗口坐标：光晕要从这里散出去
    onCenter: (Offset) -> Unit,
) {
    // 和字标、圆钮同一套混色（关于页 HyperMount 那组）：
    // 深底那组是提亮的，采出来是一块带彩边的亮块，不会像中性灰那样糊进背景，
    // 所以深底也照常采样，只有拿不到 backdrop 时才退回实色
    val sampleMark = backdrop != null
    Box(
        Modifier
            .size(90.dp)
            .onGloballyPositioned { coords ->
                onCenter(
                    coords.positionInRoot() +
                        Offset(coords.size.width / 2f, coords.size.height / 2f),
                )
            }
            .graphicsLayer {
                scaleX = logoScale
                scaleY = logoScale
                alpha = logoAlpha
            }
            .then(
                if (sampleMark) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = RoundedCornerShape(25.dp),
                        blurRadius = 150f,
                        noiseCoefficient = BlurDefaults.NoiseCoefficient,
                        colors = BlurDefaults.blurColors(blendColors = nameBlend),
                        contentBlendMode = BlendMode.DstIn,
                    )
                } else {
                    Modifier
                },
            )
            .drawWithCache {
                // 采样时白块只是 DstIn 的遮罩；没有采样时深底画白、浅底画深色
                val color = if (sampleMark || onDark) Color.White else OnPaper
                onDrawBehind { drawPath(brandMarkPath(size, 25.dp.toPx()), color) }
            },
    )
}

/**
 * 应用名的流光染色，照抄 apkeditor 的 logoBlend：
 * 先用 ColorDodge / ColorBurn 把底色压亮或压深，再叠一条品牌色，
 * 字面就会随背景流光滚出彩边。关于页、OOBE 首页、光晕共用这一份。
 *
 * 色值实时取 [OobeTuning]：调试页里改完，这几处一起变，方便对着背景调。
 *
 * @param onDark 字底是深的还是浅的（深底用提亮的一组，浅底用压暗的一组）
 */
internal fun brandNameBlend(onDark: Boolean): List<BlendColorEntry> = OobeTuning.blend(onDark)

/**
 * 品牌环的几何：圆角方块挖掉 270° 的环，缺口开在右上，环的两个端头各是一个半圆。
 *
 * 之前端头是用 `lineTo` 直接沿径向切过去的，断口是两条直边，和圆弧接不上；
 * 这里改成「外弧 → 端头半圆 → 内弧 → 端头半圆」绕一圈，缺口两侧自然收圆。
 *
 * 引导首页 90dp / 25dp 圆角，关于页 100dp / 28dp 圆角，都用同一份几何按比例缩放，
 * 保证两个页面看到的环是同一个形状。
 *
 * @param size 绘制区域（正方形边长）
 * @param corner 方块圆角半径（px）
 */
internal fun brandMarkPath(size: Size, corner: Float): Path {
    val k = size.width / 90f
    val cx = size.width / 2f
    val cy = size.height / 2f
    val ro = 25f * k              // 外半径
    val ri = 17f * k              // 内半径
    val rm = (ro + ri) / 2f       // 环中线
    val rr = (ro - ri) / 2f       // 端头半径 = 环宽的一半

    val ring = Path().apply {
        // 外弧：0°（正右）顺时针走 270° 到 270°（正上）
        arcTo(Rect(cx - ro, cy - ro, cx + ro, cy + ro), 0f, 270f, true)
        // 上方端头：圆心 (cx, cy-rm)，从 270° 顺时针半圈到 90°，凸向右侧缺口
        arcTo(Rect(cx - rr, cy - rm - rr, cx + rr, cy - rm + rr), 270f, 180f, false)
        // 内弧：从正上逆时针 270° 回到正右
        arcTo(Rect(cx - ri, cy - ri, cx + ri, cy + ri), 270f, -270f, false)
        // 右端头：圆心 (cx+rm, cy)，从 180° 顺时针半圈到 0°，凸向上方缺口
        arcTo(Rect(cx + rm - rr, cy - rr, cx + rm + rr, cy + rr), 180f, 180f, false)
        close()
    }
    // 缺口正中那个点也挖掉
    val ang = -PI / 4
    val dot = Path().apply {
        addOval(
            Rect(
                Offset(cx + cos(ang).toFloat() * rm, cy + sin(ang).toFloat() * rm),
                5f * k,
            ),
        )
    }
    val cut = Path().apply { op(ring, dot, PathOperation.Union) }
    val body = Path().apply {
        addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(corner)))
    }
    return Path().apply { op(body, cut, PathOperation.Difference) }
}

/** 圆形转场的起点：圆钮的圆心与半径（窗口坐标） */
private data class RevealSpec(val center: Offset, val radius: Float)

// ---------------- 图标光晕 ----------------

/**
 * 光晕的扩张曲线
 *
 * 原作 glow.glsl 里 `animatedTime = t/(k - t*(k-1))`，k = 1.4：
 * 起手慢、越扩越快，正好是"从图标里涌出来"的感觉。
 * k 可以在调试页里调，越大起手越慢。
 */
private fun glowEase(k: Float): Easing = Easing { x ->
    (x / (k - x * (k - 1f))).coerceIn(0f, 1f)
}

/**
 * 光晕的颜色
 *
 * 原作 `pal(p, vec3(.5), vec3(.5), vec3(1.), vec3(0., .1, .2))`，
 * 也就是三通道各错开一点相位的余弦调色板；`uCircleColorOffset` 是 0.25。
 */
private fun glowColor(g: Float): Color {
    val t = g + 0.25f
    fun ch(offset: Float): Float {
        val v = 0.5f + 0.5f * cos(2f * PI.toFloat() * (t + offset))
        return v.coerceIn(0f, 1f)
    }
    return Color(ch(0f), ch(0.1f), ch(0.2f))
}

/** 环带的亮度分布：两端为 0、正中为 1（原作 `parabola(x, 3)`） */
private fun glowFalloff(g: Float): Float {
    val v = (4f * g * (1f - g)).coerceIn(0f, 1f)
    return v * v * v
}

/**
 * 把一环光画出来：径向渐变两端透明、中间最亮，形状完全由 [colorAt] 给出的 alpha 决定。
 *
 * 走采样时这里就是一张遮罩（白色 + falloff 的 alpha），环上的像素由外面的模糊层填。
 *
 * 外径、环厚、淡出起点这三项实时读 [OobeTuning]，调试页拖滑条能直接看到变化。
 */
private fun DrawScope.drawGlowRing(
    center: Offset,
    t: Float,
    peak: Float,
    colorAt: (Float) -> Color,
) {
    if (t <= 0f || t >= 1f) return
    val outer = size.height * OobeTuning.outerScale * t
    if (outer <= 1f) return
    val thickness = size.height * OobeTuning.thickness
    val inner = (outer - thickness).coerceAtLeast(0f)
    val innerNorm = (inner / outer).coerceIn(0f, 0.998f)
    // 末尾淡出，别让环撞到屏幕边上硬生生消失
    val fadeStart = OobeTuning.fadeStart.coerceIn(0f, 1f)
    val fade = if (fadeStart >= 1f) 1f else 1f - ((t - fadeStart) / (1f - fadeStart)).coerceIn(0f, 1f)
    val a = peak * fade

    val stops = ArrayList<Pair<Float, Color>>(10)
    stops += 0f to Color.Transparent
    stops += innerNorm to Color.Transparent
    for (i in 1 until 8) {
        val g = i / 8f
        stops += (innerNorm + (1f - innerNorm) * g) to colorAt(g).copy(alpha = glowFalloff(g) * a)
    }
    stops += 1f to Color.Transparent

    drawCircle(
        brush = Brush.radialGradient(
            colorStops = stops.toTypedArray(),
            center = center,
            radius = outer,
        ),
        radius = outer,
        center = center,
    )
}

/**
 * 图标入场后的光晕：一圈柔光从图标中心往外扩
 *
 * 照 HyperCeiler 引导首页的 glow.glsl：外径长到一屏高、环带厚度约 0.45 屏高，
 * 颜色沿半径走它那条余弦调色板，亮度按抛物线分布，越靠环中间越亮。
 * 原作从 t=0 就开始扩；这里跟着图标入场动画同一起点，[OobeTuning.delayMs] 只做微调。
 *
 * 环本身不自己定色：和图标 / 字标 / 圆钮走同一套 textureBlur + brandNameBlend，
 * 环上的像素就是"背景流光被模糊、提亮之后"的颜色，跟着背景一起变色。
 */
@Composable
private fun GlowBurst(
    backdrop: LayerBackdrop?,
    // 和图标 / 字标 / 圆钮同一份混色
    nameBlend: List<BlendColorEntry>,
    center: Offset,
    animateIn: Boolean,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (!animateIn) return@LaunchedEffect
        // 和图标入场动画同时起：延迟按入场开始算，不再等 440 + 700 走完
        delay(OobeTuning.delayMs.toLong())
        progress.animateTo(
            1f,
            tween(
                OobeTuning.durationMs.toInt().coerceAtLeast(1),
                easing = glowEase(OobeTuning.easeK),
            ),
        )
    }
    // textureBlur 每帧都要跑一遍全屏模糊，环不在的那段时间不必挂着
    val running by remember {
        derivedStateOf { progress.value > 0f && progress.value < 1f }
    }
    if (!running) return

    val fill = Modifier.fillMaxSize()
    if (backdrop != null) {
        Box(
            fill.then(
                Modifier.textureBlur(
                    backdrop = backdrop,
                    shape = RectangleShape,
                    blurRadius = OobeTuning.blurRadius,
                    noiseCoefficient = BlurDefaults.NoiseCoefficient,
                    colors = BlurDefaults.blurColors(blendColors = nameBlend),
                    contentBlendMode = BlendMode.DstIn,
                ),
            ),
        ) {
            // 白色只是 DstIn 的遮罩：环的像素整块换成背景模糊 + 混色
            Canvas(fill) { drawGlowRing(center, progress.value, 1f) { Color.White } }
            Canvas(fill) {
                drawGlowRing(center, progress.value, OobeTuning.highlightAlpha) { Color.White }
            }
        }
    } else {
        // 老设备拿不到 backdrop：退回原作那条余弦调色板的实色环
        Canvas(fill) { drawGlowRing(center, progress.value, 0.62f) { glowColor(it) } }
    }
}

/**
 * 欢迎页：只有 logo 和圆钮，和原作一样不放任何说明文字
 *
 * 纵向按原作的 30 : 90dp : 40 : 20 分配（图标固定 90dp，其余三段按比例分剩余高度），
 * 所以按钮落在屏幕下方约 18% 的位置，而不是贴着底边。
 */
@Composable
private fun WelcomeStep(
    backdrop: LayerBackdrop?,
    // 图标 / 字标 / 圆钮共用的流光染色（关于页 HyperMount 同款）
    nameBlend: List<BlendColorEntry>,
    // 首页底色是深的还是浅的（反色流光的实际亮度）
    onDark: Boolean,
    // 是否播放入场动画（从第二页退回来时不再重播，直接停在终态）
    animateIn: Boolean,
    onNext: (Offset, Float) -> Unit,
) {
    // 照原作 AnimHelper.startPageLogoAnim：图标和字标共用同一套 ——
    // 缩放 0.5 → 0.95（sinOut 440ms）→ 1（cubicOut 700ms），透明度延后 60ms 淡入。
    // 两段是并行跑的，不是先淡入完再放大。
    val logoScale = remember { Animatable(if (animateIn) 0.5f else 1f) }
    val logoAlpha = remember { Animatable(if (animateIn) 0f else 1f) }
    // 字标和图标不一样：它用的是关于页那套「提亮 + 品牌色」的混合，
    // 深底上采出来是发亮的彩边字，不会像中性灰那样糊进背景，
    // 所以深底也照常采样，只有拿不到 backdrop 时才退回实色
    val sampleText = backdrop != null

    LaunchedEffect(Unit) {
        if (!animateIn) return@LaunchedEffect
        launch {
            logoScale.animateTo(0.95f, tween(440, easing = SinOut))
            logoScale.animateTo(1f, tween(700, easing = CubicOut))
        }
        delay(60)
        logoAlpha.animateTo(1f, tween(400))
    }

    // 光晕要从图标中心散出去：记一下图标在窗口里的中心，再减掉自己这一层的位置
    var layerOrigin by remember { mutableStateOf(Offset.Zero) }
    var iconCenter by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { layerOrigin = it.positionInRoot() },
    ) {
        // 光晕画在内容下面：它属于背景，不该盖住图标和字
        GlowBurst(
            backdrop = backdrop,
            nameBlend = nameBlend,
            center = iconCenter - layerOrigin,
            animateIn = animateIn,
        )

        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.weight(30f))

            Box(
                Modifier.fillMaxWidth().height(90.dp),
                contentAlignment = Alignment.Center,
            ) {
                WelcomeLogo(
                    backdrop = backdrop,
                    nameBlend = nameBlend,
                    onDark = onDark,
                    logoScale = logoScale.value,
                    logoAlpha = logoAlpha.value,
                    onCenter = { iconCenter = it },
                )
            }

            Box(Modifier.weight(40f).fillMaxWidth()) {
                // 字标：原作是一张 500×71 的字形图，按 320×45.28dp 摆，几乎顶满 320dp 宽。
                // 系统字体没有那个宽度，用 54sp 才把 "HyperMount" 撑到接近 320dp。
                // 采样成功时底色会被背景模糊整个替换，写白色只是给遮罩一个不透明的底；
                // 采样不可用（老设备）时退回固定深色。
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 20.dp)
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = logoScale.value
                            scaleY = logoScale.value
                            alpha = logoAlpha.value
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "HyperMount",
                        modifier = Modifier.then(
                            if (sampleText) {
                                Modifier.textureBlur(
                                    backdrop = backdrop,
                                    shape = RoundedCornerShape(16.dp),
                                    blurRadius = 150f,
                                    noiseCoefficient = BlurDefaults.NoiseCoefficient,
                                    colors = BlurDefaults.blurColors(blendColors = nameBlend),
                                    contentBlendMode = BlendMode.DstIn,
                                )
                            } else {
                                Modifier
                            },
                        ),
                        fontSize = 54.sp,
                        fontWeight = FontWeight.Bold,
                        // 采样时白色只是 DstIn 的遮罩底；深底直接白字，浅底没采样就退回深色
                        color = if (sampleText || onDark) Color.White else OnPaper,
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.5.dp)) {
                    WelcomeNextButton(
                        backdrop = backdrop,
                        onDark = onDark,
                        animateIn = animateIn,
                        onClick = onNext,
                    )
                }
            }

            Spacer(Modifier.weight(20f))
        }
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
            // 每页自带不透明底：圆形转场撤掉遮罩后，底下那层还在渐变的欢迎页背景
            // 就不会透出来（否则会看到一次「闪回首页」）
            .background(MiuixTheme.colorScheme.surface)
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
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
