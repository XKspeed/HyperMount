package com.xkspeed.HyperMount.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowCompat
import com.xkspeed.HyperMount.R
import com.xkspeed.HyperMount.core.Module
import com.xkspeed.HyperMount.core.ModuleState
import com.xkspeed.HyperMount.core.Root
import com.xkspeed.HyperMount.i18n.AppLang
import com.xkspeed.HyperMount.i18n.t
import com.xkspeed.HyperMount.ui.component.effect.BgEffectBackground
import com.xkspeed.HyperMount.ui.component.glass.LiquidTopBarButton
// 二级界面那颗玻璃返回键用的是 kyant backdrop（和 miuix 的 LayerBackdrop 不是一套），
// 两边的 layerBackdrop 同名，这里起别名免得跟上面的 miuix 版本撞
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as kyantRememberLayerBackdrop
import com.xkspeed.HyperMount.ui.components.LiquidSelectHost
import com.xkspeed.HyperMount.ui.components.LiquidSelectPreference
import com.xkspeed.HyperMount.ui.components.rememberLiquidSelectState
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
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 引导总步数：欢迎 / 权限 / 协议 / 基础设置 / 音量键 / 完成 */
private const val ONBOARD_STEPS = 6

/** 权限页 / 协议页 / 基础设置页在步进里的下标（写数字太容易错位，统一放这里） */
private const val STEP_WELCOME = 0
private const val STEP_PERMISSION = 1
private const val STEP_TERMS = 2
private const val STEP_BASIC = 3
private const val STEP_VOLUME = 4
private const val STEP_DONE = 5

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
 * 5 = 后续页照 HyperCeiler 重排：返回箭头 + 居中标题 + 卡片行，文案精简。
 * 6 = 照 HyperCeiler provision 的页序重做：权限设置 / 协议与声明 / 基础设置 / 音量键 / 完毕。
 */
const val ONBOARD_VERSION = 6

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

/** quartOut：原作 Folme 的 FolmeEase.quartOut，1 − (1 − t)⁴ */
private val QuartOut = Easing { x -> 1f - (1f - x) * (1f - x) * (1f - x) * (1f - x) }

/**
 * 首次启动引导
 *
 * 视觉照 HyperCeiler 的 provision（MIUI 首次开机向导）来：
 * 欢迎页黑底 + 呼吸光斑 + 居中大图标，点圆形箭头进入后面几页；
 * 后面几页是 56dp 返回箭头 + 居中 32sp 标题 + 卡片行 + 底部 50dp 主按钮。
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

    var step by remember { mutableIntStateOf(if (startAtVolume) STEP_VOLUME else STEP_WELCOME) }
    var agreed by remember { mutableStateOf(false) }

    // 第 2 页强制停留 3 秒：进来从 3 倒数 归零前「同意并继续」不可点
    // 回退再进来重新计时 免得拿后退绕过
    var agreeLeft by remember { mutableIntStateOf(3) }
    LaunchedEffect(step) {
        agreeLeft = 3
        if (step != STEP_TERMS) return@LaunchedEffect
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

    // 进权限页就检查一遍 root / 模块：这一页要显示它们的实时状态
    LaunchedEffect(step, envRetry) {
        if (step != STEP_PERMISSION) return@LaunchedEffect
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
        if (step != STEP_VOLUME) return@LaunchedEffect
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

    // ---- 权限页：通知 / 自启动 ----
    // 通知权限从 MainActivity 的开屏请求搬到这里：引导页里能看到状态、被拒了还能再点一次
    var notifOn by remember { mutableStateOf(notificationsAllowed(context)) }
    var notifAsked by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notifAsked = true
        notifOn = notificationsAllowed(context)
    }
    // 自启动没有可靠的系统查询接口 只记「引导页点过去过」这一笔
    var autostartOn by remember { mutableStateOf(UiConfigState.autostartPrompted) }

    // 回到权限页 / 从系统设置切回来时重新对一次通知状态
    LaunchedEffect(step) {
        if (step == STEP_PERMISSION) notifOn = notificationsAllowed(context)
    }

    // 基础设置页要显示运行模式和日志目录：进页面读一次模块配置
    var conf by remember { mutableStateOf(ModuleState()) }
    LaunchedEffect(step) {
        if (step == STEP_BASIC) {
            runCatching { Module.readState() }.getOrNull()?.let { conf = it }
        }
    }

    val canNext = when (step) {
        STEP_PERMISSION -> !envChecking && envReady
        STEP_TERMS -> agreed && agreeLeft == 0
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
    // 首页和完成页都是反色流光：系统深色时反而偏浅，这时状态栏要放深色图标
    val surface = MiuixTheme.colorScheme.surface
    val darkPage = if (step == STEP_WELCOME || step == STEP_DONE) homeDark
    else surface.luminance() < 0.5f
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
    // 这一次圆形转场是「完成页 → 主页」：遮罩里不画下一页，只留底色
    var finishing by remember { mutableStateOf(false) }

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
            step = STEP_PERMISSION
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
            step = STEP_WELCOME
            reveal = spec
            withFrameNanos { }
            revealProgress.animateTo(0f, tween(420, easing = FastOutLinearInEasing))
            reveal = null
            transitionBusy = false
        }
        return true
    }

    /**
     * 完成页点圆钮：和首页同一条圆形放大，圆长满整屏再把画面交给主页
     *
     * 圆里不画下一页 —— 主页在引导外面，画不进这一层，所以只铺底色。
     * 底色用的就是主页那张 surface，圆扩到满屏时两边颜色一致，接得上。
     */
    fun startFinish(c: Offset, r: Float) {
        if (transitionBusy) return
        transitionBusy = true
        scope.launch {
            revealProgress.snapTo(0f)
            finishing = true
            reveal = RevealSpec(c, r)
            revealProgress.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
            // 等圆真的画满一帧再交棒：同一帧切的话，主页可能先出来，看到一次闪
            withFrameNanos { }
            onDone()
        }
    }

    fun next() {
        // 环境没过 后面几步都不该放行
        if (step == STEP_PERMISSION && (!envReady || envChecking)) return
        if (step == STEP_VOLUME && !volDone) volSkip = true
        if (step < ONBOARD_STEPS - 1) step++
    }

    /**
     * 退一步
     *
     * 系统返回键和页面左上角的箭头走同一条路：都能从第 2 页缩回欢迎页，
     * 转场那半秒里都不响应，免得把圆形动画撕成两半。
     */
    fun goBack() {
        if (transitionBusy) return
        if (step == STEP_WELCOME) {
            // 只剩回看入口这一种情况会走到这里：直接退回调试页
            onCancel?.invoke()
        } else if (step == STEP_PERMISSION && startCollapse()) {
            // 1 → 0 走圆形收缩，和进场的圆形展开对称
        } else {
            if (step == STEP_VOLUME && !volDone) volSkip = true
            step--
        }
    }

    // 只能一步步往回退 退到第一步就不再拦 由系统退出 App
    BackHandler(enabled = step > 0 || onCancel != null) { goBack() }

    val bg by animateColorAsState(
        // 首页底色要跟反色流光同调：系统浅色时首页是深流光，垫色不能再是浅粉，
        // 不然背景层淡出/退场的那一下会闪一层粉
        targetValue = if (step == STEP_WELCOME) {
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
        // 完成页也要这层：图标、字标、圆钮都靠它采样，撤掉就只剩实色块
        AnimatedVisibility(
            visible = step == STEP_WELCOME || step == STEP_DONE,
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
                // 首页 ↔ 权限页 是圆形放大/收缩自己走完的，这里必须"立刻"换页：
                // 用 tween(0) 仍会经过一帧动画系统，遮罩撤掉时那一帧露出来的还是原来那页
                if ((initialState == STEP_WELCOME && targetState == STEP_PERMISSION) ||
                    (initialState == STEP_PERMISSION && targetState == STEP_WELCOME)
                ) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    // 照 ceiler 的 provision_slide_in_right / provision_slide_out_left：
                    // 350ms、accelerate-decelerate、整屏平移，不做淡入淡出
                    val dir = if (targetState > initialState) 1 else -1
                    val slide = tween<androidx.compose.ui.unit.IntOffset>(
                        350,
                        easing = FastOutSlowInEasing,
                    )
                    slideInHorizontally(slide) { it * dir } togetherWith
                        slideOutHorizontally(slide) { -it * dir }
                }
            },
            modifier = Modifier.fillMaxSize(),
            label = "onboarding",
        ) { index ->
            when (index) {
                STEP_WELCOME -> WelcomeStep(backdrop, nameBlend, homeDark, animateIn = !introDone) { c, r ->
                    startReveal(c, r)
                }
                STEP_PERMISSION -> PermissionStep(
                    env = env,
                    checking = envChecking,
                    notifOn = notifOn,
                    autostartOn = autostartOn,
                    onRequestNotif = {
                        // 系统只在第一次弹框；用户拒过一次后再 launch 什么都不会发生，
                        // 这时直接把人送到系统通知设置，免得点了没反应
                        if (notifAsked || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            openNotificationSettings(context)
                        } else {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onRetry = { envRetry++ },
                    onOpenAutostart = {
                        if (openAutostart(context)) {
                            autostartOn = true
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
                    onBack = ::goBack,
                )
                STEP_TERMS -> TermsStep(onBack = ::goBack)
                STEP_BASIC -> BasicSettingsStep(
                    logMode = conf.logMode,
                    logDir = conf.logDir,
                    onSetLogMode = { v ->
                        // 先按选中的显示，写失败再退回去 —— 引导页没有设置页那种回读，
                        // 乐观更新错了会一直挂着假状态
                        val old = conf.logMode
                        conf = conf.copy(logMode = v.toString())
                        scope.launch {
                            val ok = runCatching { Module.setConf("LOG_MODE", v.toString()) }
                                .getOrDefault(false)
                            if (!ok) conf = conf.copy(logMode = old)
                        }
                    },
                    onLogDirPicked = { path ->
                        val old = conf.logDir
                        conf = conf.copy(logDir = path)
                        scope.launch {
                            val ok = runCatching { Module.setConf("LOG_DIR", path) }
                                .getOrDefault(false)
                            if (!ok) conf = conf.copy(logDir = old)
                        }
                    },
                    onBack = ::goBack,
                )
                STEP_VOLUME -> VolumeStep(
                    up = upState,
                    down = downState,
                    hint = volHint,
                    canRetry = !volAlready,
                    onRetry = {
                        volSkip = false
                        volRetry++
                    },
                    onBack = ::goBack,
                )
                STEP_DONE -> DoneStep(
                    backdrop = backdrop,
                    nameBlend = nameBlend,
                    onDark = homeDark,
                    onNext = ::startFinish,
                )
                else -> Unit
            }
        }

        // 圆形放大层：从圆钮长成整屏。首页出发时盖住欢迎页、露出权限页；
        // 完成页出发时一直扩到满屏，把画面交给主页
        // 半径在 draw 阶段读，只重绘不重组 —— 否则这 520ms 里下一页会被反复重组
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
                // 遮罩层里画的还是权限页：参数要跟正式渲染的一致，不然揭开时状态会跳。
                // 完成页是朝主页长，那一页画不进这一层，圆内只留底色
                if (!finishing) {
                    PermissionStep(
                        env = env,
                        checking = envChecking,
                        notifOn = notifOn,
                        autostartOn = autostartOn,
                        onRequestNotif = {},
                        onRetry = {},
                        onOpenAutostart = {},
                        onBack = {},
                    )
                }
            }
        }

        // 欢迎页的按钮按 30/40/20 的比例落位（在 WelcomeStep 内部），
        // 其余页统一用原作后几页那种 50dp 胶囊贴底。
        // 圆形转场刚结束时先淡入，别跟着那一下硬切出来
        AnimatedVisibility(
            visible = step > STEP_WELCOME && step < STEP_DONE,
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
                // 协议页的勾选行贴在主按钮上方：不套卡片，点一下只改勾。
                // Column 是贴底对齐的，这一块长高不会把按钮推走，只会向上展开
                AnimatedVisibility(
                    visible = step == STEP_TERMS,
                    enter = fadeIn(tween(220)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
                ) {
                    AgreeRow(
                        agreed = agreed,
                        agreeLeft = agreeLeft,
                        onToggle = { agreed = !agreed },
                    )
                }

                // 和 ceiler 一样，页面下方只有一颗 50dp 的确定键；
                // 完成页自带首页那颗圆钮，这里不重复给
                NextPillButton(
                    label = when (step) {
                        STEP_TERMS -> t("同意并继续", "Agree and continue")
                        else -> t("继续", "Continue")
                    },
                    enabled = canNext,
                    onClick = ::next,
                )

                // 音量页跳过：次要动作给文字入口
                if (step == STEP_VOLUME && !volDone) {
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
 * 原作 startup 的标准配置：96dp 纯白圆、深色箭头，容器底部留 4.5dp。
 * 入场比 logo 晚 700ms（BUTTON_ENTER_DELAY_MS），从 0.8 弹到 1（spring 0.9 / 0.78）。
 */
@Composable
private fun WelcomeNextButton(
    backdrop: LayerBackdrop?,
    onDark: Boolean,
    // 从第二页退回来时按钮已经在位，不再重播「延迟 700ms 弹入」
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
        // 原作 BUTTON_ENTER_DELAY_MS = 700：logo 起手 0.7 秒后按钮才出来
        delay(700)
        // 原作 AnimHelper.startPageBtnAnim：alpha 0→1、scale 0.8→1，
        // 用 FolmeEase.spring(damping 0.9, response 0.78)，不是固定时长的缓动
        appear.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 65f))
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
                // 入场的缩放起点也是 0.8（原作 AnimState start）
                val s = pressScale * (0.8f + 0.2f * appear.value)
                scaleX = s
                scaleY = s
                alpha = appear.value
            }
            .size(96.dp)
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
            modifier = Modifier.size(40.dp, 27.dp),
        )
    }
}

/**
 * 后几页的下一步
 *
 * 和原作一样高 50dp、最宽 336dp、距底 44dp；圆角按 provision_next_btn_background 走 16dp，
 * 不是全圆胶囊。
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
            .clip(RoundedCornerShape(16.dp))
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
 * 光晕的峰值不透明度
 *
 * 走实色余弦环这一版（不再采样背景）的值：0.62 在深浅两种底色上都能看出「一圈光」，
 * 再高就会在浅底上糊成一片白。
 */
private const val GLOW_PEAK = 0.62f

/**
 * 图标入场后的光晕：一圈柔光从图标中心往外扩
 *
 * 照 HyperCeiler 引导首页的 glow.glsl：外径长到一屏高、环带厚度约 0.45 屏高，
 * 颜色沿半径走它那条余弦调色板，亮度按抛物线分布，越靠环中间越亮。
 * 原作从 t=0 就开始扩；这里跟着图标入场动画同一起点，[OobeTuning.delayMs] 只做微调。
 *
 * 颜色直接用原作那条余弦调色板：之前试过把环换成「背景模糊 + 提亮」的采样，
 * 在浅色欢迎底上会跟着底图一起泛白，反而看不出环——实色这版在深底、浅底、
 * 完成页的纯色底上都一样干净。
 */
@Composable
private fun GlowBurst(
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
    // 环没在扩的那段时间不必挂着一个全屏 Canvas
    val running by remember {
        derivedStateOf { progress.value > 0f && progress.value < 1f }
    }
    if (!running) return

    // 原作那条余弦调色板的实色环
    Canvas(Modifier.fillMaxSize()) {
        drawGlowRing(center, progress.value, GLOW_PEAK) { glowColor(it) }
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
    // 字标：首页是品牌名，完成页换成「设置完毕」
    wordmark: String = "HyperMount",
    onNext: (Offset, Float) -> Unit,
) {
    // 照原作 AnimHelper.startPageLogoAnim：图标和字标共用同一套 ——
    // 缩放 0.8 → 0.95（sinOut 440ms）→ 1（cubicOut 700ms），透明度延后 60ms 淡入。
    // 两段是顺序跑的，不是先淡入完再放大。
    val logoScale = remember { Animatable(if (animateIn) 0.8f else 1f) }
    val logoAlpha = remember { Animatable(if (animateIn) 0f else 1f) }
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

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { layerOrigin = it.positionInRoot() },
    ) {
        // 光晕画在内容下面：它属于背景，不该盖住图标和字
        GlowBurst(
            center = iconCenter - layerOrigin,
            animateIn = animateIn,
        )

        // 照原版 startup_layout_new：
        // 品牌图中心落在屏高 41%（guideline 0.41）；下半区按 40 / 39 / 21 三段权重，
        // 中间那段是圆钮、底对齐，所以圆钮底边离屏底正好 21%。
        // 我们这里是「环 + 字标」两行，高度按 90 + 20 + 约 66 折成 176dp。
        val brandHeight = 176.dp
        val brandTop = maxHeight * 0.41f - brandHeight / 2

        Column(
            Modifier
                .fillMaxWidth()
                .offset(y = brandTop)
                .height(brandHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WelcomeBrand(
                backdrop = backdrop,
                nameBlend = nameBlend,
                onDark = onDark,
                wordmark = wordmark,
                scale = logoScale.value,
                alpha = logoAlpha.value,
                onCenter = { iconCenter = it },
            )
        }

        // 原版第 2 段（39%）的圆钮底对齐，距屏底 21%
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = maxHeight * 0.21f),
        ) {
            WelcomeNextButton(
                backdrop = backdrop,
                onDark = onDark,
                animateIn = animateIn,
                onClick = onNext,
            )
        }
    }
}

/**
 * 品牌块：90dp 采样图标 + 20dp 间距 + 54sp 流光字标
 *
 * 首页和完成页共用这一块，字标字号只在这里定义一处 —— 两页的图标大小、
 * 字重、字距、染色方式才不会各走一套。缩放 / 透明度由调用方给，
 * 因为两页的入场节奏不一样（首页缩放淡入，完成页上浮淡入）。
 */
@Composable
private fun WelcomeBrand(
    backdrop: LayerBackdrop?,
    nameBlend: List<BlendColorEntry>,
    onDark: Boolean,
    wordmark: String,
    scale: Float,
    alpha: Float,
    onCenter: (Offset) -> Unit,
) {
    // 字标和图标不一样：它用的是关于页那套「提亮 + 品牌色」的混合，
    // 深底上采出来是发亮的彩边字，不会像中性灰那样糊进背景，
    // 所以深底也照常采样，只有拿不到 backdrop 时才退回实色
    val sampleText = backdrop != null

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().height(90.dp),
            contentAlignment = Alignment.Center,
        ) {
            WelcomeLogo(
                backdrop = backdrop,
                nameBlend = nameBlend,
                onDark = onDark,
                logoScale = scale,
                logoAlpha = alpha,
                onCenter = onCenter,
            )
        }

        Spacer(Modifier.height(20.dp))

        // 字标：原作是一张 500×71 的字形图，按 320×45.28dp 摆，几乎顶满 320dp 宽。
        // 系统字体没有那个宽度，用 54sp 才把 "HyperMount" 撑到接近 320dp。
        // 采样成功时底色会被背景模糊整个替换，写白色只是给遮罩一个不透明的底；
        // 采样不可用（老设备）时退回固定深色。
        Text(
            wordmark,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .then(
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
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------- 第 3 页：协议与声明 ----------------

/**
 * 协议与声明
 *
 * 照 HyperCeiler 的条款页（provision_terms_and_statement）：正文一整块，
 * 底部一颗「同意并继续」。勾选行不在这里，它和主按钮一起固定在页面底部，
 * 3 秒倒计时挂在勾选行右侧。
 */
@Composable
private fun TermsStep(onBack: () -> Unit) {
    StepScaffold(
        title = t("协议与声明", "Terms & statement"),
        icon = painterResource(R.drawable.ic_oobe_terms),
        onBack = onBack,
        // 底部除了主按钮还有一行勾选，正文到底时得让开这块
        bottomSpace = 185.dp,
    ) {
        // 正文不要卡片底：整段文字本身就是内容，再套一层灰底只是把字关进框里。
        // 勾选行在页面底部、主按钮上方，不在这块滚动区里
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            // 透明底之后只剩排版作用：内容自己带 16dp，把卡片默认内边距收掉，免得双倍内缩
            insideMargin = PaddingValues(0.dp),
            colors = CardDefaults.defaultColors(color = Color.Transparent),
        ) {
            Text(
                t(
                    "本应用通过模块脚本替换系统界面（SystemUI），用于界面出错后的恢复与日志排查。\n\n" +
                        "刷入模块、替换界面文件都存在风险。操作前请自行备份，因刷机、模块冲突、" +
                        "系统版本不匹配造成的后果由使用者自行承担。\n\n" +
                        "应用不收集、不上传任何数据，日志只写入你选择的本地目录；" +
                        "卸载模块后系统界面恢复原样。\n\n" +
                        "点击「同意并继续」即表示你已理解并接受以上内容。",
                    "This app swaps the system UI (SystemUI) through module scripts, so you can " +
                        "recover the interface and read logs when something goes wrong.\n\n" +
                        "Flashing the module and replacing UI files both carry risk. Back up " +
                        "first — anything caused by flashing, module conflicts or a mismatched " +
                        "system version is on you.\n\n" +
                        "Nothing is collected or uploaded. Logs stay in the folder you pick, and " +
                        "removing the module restores the original UI.\n\n" +
                        "Tapping \"Agree and continue\" means you have read and accepted this.",
                ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                fontSize = 13.sp,
                lineHeight = 21.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 同意条款的勾选行
 *
 * 不套卡片，贴在底部主按钮上方。整行可点，但按下不出任何底色：
 * 反馈只靠勾选框自己变，别让整块区域闪一下像被选中的列表项。
 */
@Composable
private fun AgreeRow(
    agreed: Boolean,
    agreeLeft: Int,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            // 横向 8dp：外层容器已经留了 20dp，加上就是 28dp —— 和上面正文那行的
            // 左边界（卡片 12 + 内文 16）严格对齐，勾选框不会比正文多缩或少缩一截
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            state = if (agreed) ToggleableState.On else ToggleableState.Off,
            onClick = onToggle,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            t("我已仔细阅读并同意上述条款", "I have read and agree to the terms above"),
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
}

/**
 * 后几页的统一骨架（照 HyperCeiler 的 provision）
 *
 * 56dp 操作栏只放一颗返回键 → 居中 32sp 标题 → 卡片组。
 * 内容自己滚，底部留白给外层那颗 50dp 主按钮；协议页多一行勾选，[bottomSpace] 加高。
 */
@Composable
private fun StepScaffold(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    // 标题上方的大图标（照 ceiler 的 preview_image：70dp，上 10 / 下 8）
    icon: androidx.compose.ui.graphics.painter.Painter? = null,
    bottomSpace: Dp = 130.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 返回键用二级界面那颗玻璃圆钮：它要采样底下的内容才成玻璃，
    // 所以整页先录进一层 layer，按钮自己浮在采样层之上（否则会拍到自己）
    val backdrop = kyantRememberLayerBackdrop()
    val surface = MiuixTheme.colorScheme.surface

    Box(Modifier.fillMaxSize().background(surface)) {
        Column(
            Modifier
                .fillMaxSize()
                .kyantLayerBackdrop(backdrop)
                // 不透明底放在 layer 里面：圆形转场撤掉遮罩后，底下那层还在渐变的欢迎页背景
                // 就不会透出来（否则会看到一次「闪回首页」）
                .background(surface)
                .stableStatusBarsPadding(),
        ) {
            // 顶部 56dp 只占位，返回键浮在这层之上
            Spacer(Modifier.height(56.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = bottomSpace),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (icon != null) {
                    Spacer(Modifier.height(10.dp))
                    Image(
                        painter = icon,
                        contentDescription = null,
                        modifier = Modifier.size(70.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // 标题区照原版 provision_lyt_title：整块最小 72dp、标题本体最小 42dp，
                // 标题 32sp / title1、medium、居中、最多 2 行，左右各留 12dp
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        title,
                        fontSize = 32.sp,
                        lineHeight = 40.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier
                            .heightIn(min = 42.dp)
                            .wrapContentHeight(align = Alignment.CenterVertically),
                    )

                    // 副标题：14sp / body2，离标题 6dp、离下面内容 9dp
                    if (subtitle != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            subtitle,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(9.dp))
                    }
                }

                Spacer(Modifier.height(26.dp))

                content()
            }
        }

        // 原版 actionbar 高 56dp、左右 12dp、按钮 44dp；这里沿用二级界面那颗玻璃圆钮，
        // 尺寸与位置按原版来，看起来仍是同一个返回键
        Box(
            Modifier
                .stableStatusBarsPadding()
                .height(56.dp)
                .padding(start = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            LiquidTopBarButton(
                onClick = onBack,
                backdrop = backdrop,
                icon = painterResource(R.drawable.ic_back),
                contentDescription = t("返回", "Back"),
                iconSize = 19.dp,
                iconOffset = DpOffset((-2).dp, 0.dp),
                buttonHeight = 44.dp,
                draggable = true,
                adaptiveLuminance = true,
            )
        }
    }
}

// ---------------- 第 2 页：权限设置 ----------------

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

/**
 * 通知能不能弹
 *
 * Android 13 起要运行时权限，下面几版看通知总开关（用户可能在设置里关掉）。
 * 两个条件都要过 —— 只看权限会漏掉「授权了但通知被关」的情况。
 */
private fun notificationsAllowed(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    return NotificationManagerCompat.from(context).areNotificationsEnabled()
}

/** 去系统通知设置：被拒过一次之后系统不再弹框，只能从设置里改 */
private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/**
 * 权限设置
 *
 * 照 HyperCeiler 的 provision_permission：一条一张卡，右侧只有通过时才出勾。
 * ceiler 卡的是网络，这里卡 ROOT 与模块 —— 缺这两样应用什么都干不了，
 * 通知与自启动只提示不拦。
 */
@Composable
private fun PermissionStep(
    env: EnvResult,
    checking: Boolean,
    notifOn: Boolean,
    autostartOn: Boolean,
    onRequestNotif: () -> Unit,
    onRetry: () -> Unit,
    onOpenAutostart: () -> Unit,
    onBack: () -> Unit,
) {
    val ready = env.root && env.moduleOk

    StepScaffold(
        title = t("权限设置", "Permissions"),
        onBack = onBack,
        icon = painterResource(R.drawable.ic_oobe_permission),
    ) {
        // 已通过的项不写说明：勾本身就是状态，再补一句「已授权」纯属占地方。
        // 整页一张卡，行与行直接相邻 —— 分三张卡会在视觉上把同一件事切成三段
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            PermissionRow(
                title = t("通知", "Notifications"),
                summary = if (notifOn) null else t("点一下开启", "Tap to turn on"),
                checked = notifOn,
                onClick = onRequestNotif,
            )
            PermissionRow(
                title = t("ROOT 权限", "ROOT access"),
                summary = when {
                    checking -> t("检测中…", "Checking…")
                    env.root -> null
                    else -> t("未授权", "Not granted")
                },
                checked = env.root,
                onClick = if (checking) null else onRetry,
            )
            PermissionRow(
                title = t("救砖模块", "Rescue module"),
                summary = when {
                    checking -> t("检测中…", "Checking…")
                    env.moduleOk -> null
                    env.moduleVersion.isBlank() -> t("未安装", "Not installed")
                    else -> t("不是 XKspeed 版", "Not an XKspeed build")
                },
                checked = env.moduleOk,
                onClick = if (checking) null else onRetry,
            )
            PermissionRow(
                title = t("自启动", "Autostart"),
                checked = autostartOn,
                onClick = onOpenAutostart,
            )
        }

        // 没通过时说清卡在哪一项：它决定了按钮为什么点不动
        if (!checking && !ready) {
            Spacer(Modifier.height(14.dp))
            Text(
                when {
                    !env.root -> t(
                        "先在 ROOT 管理器里授权 再点上面重新检测",
                        "Grant HyperMount in your ROOT manager, then tap above to re-check",
                    )
                    env.moduleVersion.isBlank() -> t(
                        "先刷入模块 重启后再回来检测",
                        "Flash the module, reboot, then come back",
                    )
                    else -> t(
                        "当前模块不是 XKspeed 版 请换成官方发布的那份",
                        "Not an XKspeed build — use the official release",
                    )
                },
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MiuixTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 35.dp),
            )
        }
    }
}

/**
 * 权限页的一行
 *
 * 用现成的 [BasicComponent]：标题 + 说明 + 行尾状态。
 * 通过时行尾出一枚勾（ceiler 就是「通过才显示」），没通过就空着 ——
 * 用红叉反而像出了错，这里只表达「还没达成」。
 *
 * 说明文字是检测结果驱动的（检测中… / 未授权 / 未安装，通过后整行不写），
 * 有它没它行高差一行，不补过渡的话整张卡片会在检测落地那一帧突然长高。
 */
@Composable
private fun PermissionRow(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onClick: (() -> Unit)? = null,
) {
    BasicComponent(
        // 行高跟着说明文字增减，220ms 走完，和页面里其他过渡一个节奏
        modifier = Modifier.animateContentSize(
            animationSpec = tween(220, easing = FastOutSlowInEasing),
        ),
        title = title,
        summary = summary,
        onClick = onClick,
        endActions = {
            if (checked) {
                Image(
                    painter = painterResource(R.drawable.ic_oobe_check),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary),
                    modifier = Modifier.size(22.dp),
                )
            }
        },
    )
}

// ---------------- 第 4 页：基础设置 ----------------

/** 运行模式三档（和界面设置页同一套文案，现取现译） */
private fun oobeModeLabels(): List<String> = listOf(
    t("完全禁用", "Fully disabled"),
    t("仅日志", "Logs only"),
    t("日志 + 救砖", "Logs + rescue"),
)

/**
 * 基础设置
 *
 * 照 HyperCeiler 的 provision_basic_settings：运行模式 / 日志目录 / 语言，
 * 都是下拉或点选行，走到这里就能把应用配到能用的状态，之后在设置里还能改。
 */
@Composable
private fun BasicSettingsStep(
    logMode: String,
    logDir: String,
    onSetLogMode: (Int) -> Unit,
    onLogDirPicked: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modeIndex = (logMode.toIntOrNull() ?: 0).coerceIn(0, 2)
    // 下拉和设置页同一套：LiquidSelectPreference 画行，页面根层的 Host 画弹层
    val select = rememberLiquidSelectState()

    // 复用设置页那套 SAF 选择器：选完直接落盘，不解析出真实路径就让人重选
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val path = resolveFolderResult(context, result.data).path
        if (path == null) {
            Toast.makeText(
                context,
                t("这个位置拿不到真实路径 换一个目录", "Can't resolve a real path here — pick another folder"),
                Toast.LENGTH_SHORT,
            ).show()
        } else {
            onLogDirPicked(path)
        }
    }

    Box(Modifier.fillMaxSize()) {
        StepScaffold(
            title = t("基础设置", "Basic settings"),
            onBack = onBack,
            icon = painterResource(R.drawable.ic_oobe_settings),
        ) {
            // 三项同属「让应用能用起来」这一件事，放一张卡里连着看更顺
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                LiquidSelectPreference(
                    title = t("运行模式", "Run mode"),
                    items = oobeModeLabels(),
                    selectedIndex = modeIndex,
                    onSelectedIndexChange = onSetLogMode,
                    state = select,
                )
                ArrowPreference(
                    title = t("日志目录", "Log directory"),
                    summary = logDir.ifBlank { t("未设置 · 点击选择目录", "Not set · tap to choose") },
                    onClick = {
                        runCatching { folderPicker.launch(buildFolderPickIntent(context)) }
                            .onFailure {
                                Toast.makeText(
                                    context,
                                    t("打不开文件选择器", "Can't open the file picker"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                    },
                )
                LiquidSelectPreference(
                    title = t("语言", "Language"),
                    items = listOf(t("跟随系统", "Follow system"), "简体中文", "English"),
                    selectedIndex = AppLang.pref,
                    onSelectedIndexChange = { idx ->
                        AppLang.setPref(idx, context)
                        // 模块端（安装提示 / 操作台）也按这个值判断中英文，写不进去也不影响 App
                        scope.launch { runCatching { Module.setConf("UI_LANG", idx.toString()) } }
                    },
                    state = select,
                )
            }
        }

        // 引导页没有整页玻璃采样的底，弹层走实色面板（backdrop 传 null）
        LiquidSelectHost(select, backdrop = null)
    }
}

// ---------------- 第 5 页：音量键校验 ----------------

@Composable
private fun VolumeStep(
    up: KeyState,
    down: KeyState,
    hint: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    StepScaffold(
        title = t("音量键校验", "Volume key check"),
        subtitle = t("先按一次音量 + 再按一次音量 −", "Press Volume + then Volume −"),
        icon = painterResource(R.drawable.ic_oobe_volume),
        onBack = onBack,
    ) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            KeyRow(t("音量 +", "Volume +"), up)
            KeyRow(t("音量 −", "Volume −"), down)
        }

        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 35.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (up is KeyState.Waiting || down is KeyState.Waiting) {
                PulsingDot()
                Spacer(Modifier.width(8.dp))
            }
            Text(
                hint.ifBlank { t("准备中…", "Getting ready…") },
                fontSize = 13.sp,
                lineHeight = 19.sp,
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
            Spacer(Modifier.height(6.dp))
            TextAction(t("重新校验", "Check again"), onRetry)
        }
    }
}

@Composable
private fun KeyRow(label: String, state: KeyState) {
    BasicComponent(
        title = label,
        endActions = {
            Text(
                when (state) {
                    KeyState.Idle -> t("等待", "Waiting")
                    KeyState.Waiting -> t("请按键…", "Press a key…")
                    KeyState.Passed -> t("已识别", "Detected")
                    KeyState.Failed -> t("没读到", "Not read")
                },
                fontSize = 14.sp,
                color = when (state) {
                    KeyState.Passed -> MiuixTheme.colorScheme.primary
                    KeyState.Failed -> MiuixTheme.colorScheme.error
                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
        },
    )
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

// ---------------- 第 6 页：设置完毕 ----------------

/**
 * 设置完毕
 *
 * 品牌图和首页共用同一块（90dp 图标 + 54sp 字标），位置照原版 complete_layout
 * 落在屏高 41%，底部 38dp 一颗 50dp 镂空按钮。
 * 背景与光晕仍然是首页那套流光，首尾是同一块底，不会在最后一页突然换画风。
 * 按钮按下后和首页圆钮一样，从按钮中心长出一整屏圆、交棒给主页。
 */
@Composable
private fun DoneStep(
    backdrop: LayerBackdrop?,
    nameBlend: List<BlendColorEntry>,
    onDark: Boolean,
    // 和首页同一个回调：给出按钮的圆心与半径，圆从那里长满整屏
    onNext: (Offset, Float) -> Unit,
) {
    // 照原作 AnimHelper.endPageLogoAnim：logo 不缩放，
    // 从下方 100px 处上浮到原位并淡入，一条 quartOut 走完 1500ms。
    // 字标跟图标是同一块，所以跟着一起上浮，不再单独做入场。
    val enter = remember { Animatable(0f) }
    // 原作 endPageBtnAnim：按钮等 1200ms 再淡入，sinOut 450ms
    val btnAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { enter.animateTo(1f, tween(1500, easing = QuartOut)) }
        delay(1200)
        btnAlpha.animateTo(1f, tween(450, easing = SinOut))
    }

    var layerOrigin by remember { mutableStateOf(Offset.Zero) }
    var iconCenter by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { layerOrigin = it.positionInRoot() },
    ) {
        GlowBurst(
            center = iconCenter - layerOrigin,
            animateIn = true,
        )

        // 和首页同一块品牌图：90dp 采样图标 + 54sp 流光字标，首尾呼应
        val blockHeight = 176.dp
        Column(
            Modifier
                .fillMaxWidth()
                .offset(y = maxHeight * 0.41f - blockHeight / 2)
                .height(blockHeight)
                // 上浮量是原版的 100px（AnimState 里 TRANSLATION_Y 直接按像素写死）
                .graphicsLayer {
                    alpha = enter.value
                    translationY = 100f * (1f - enter.value)
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WelcomeBrand(
                backdrop = backdrop,
                nameBlend = nameBlend,
                onDark = onDark,
                wordmark = t("设置完毕", "All set"),
                scale = 1f,
                alpha = 1f,
                onCenter = { iconCenter = it },
            )
        }

        // 原版 GlassButton 的位置与尺寸：高 50dp、最大宽 336dp、距底 38dp
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 38.dp)
                .graphicsLayer { alpha = btnAlpha.value },
            contentAlignment = Alignment.Center,
        ) {
            HollowActionButton(
                label = t("开始使用", "Get started"),
                backdrop = backdrop,
                onDark = onDark,
                onClick = onNext,
            )
        }
    }
}

/**
 * 完成页底部那颗 50dp 镂空卡片按钮
 *
 * 尺寸照原版 GlassButton：高 50dp、最大宽 336dp、圆角 16、字 17sp。
 * 视觉上仍是完整一张玻璃卡片（白色遮罩 + 品牌混色采样），
 * 但「开始使用」四个字的位置被真正挖空 —— 字缝里透出来的是背景流光，
 * 而不是在卡片上再盖一层深色字。
 */
@Composable
private fun HollowActionButton(
    label: String,
    backdrop: LayerBackdrop?,
    onDark: Boolean,
    onClick: (Offset, Float) -> Unit,
) {
    val blend = remember(onDark, OobeTuning.colors(onDark).toList()) {
        brandNameBlend(onDark = onDark)
    }
    val shape = RoundedCornerShape(16.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "hollowActionPress",
    )

    var center by remember { mutableStateOf(Offset.Zero) }
    var radius by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 336.dp)
            .height(50.dp)
            .onGloballyPositioned { coords ->
                center = coords.positionInRoot() +
                    Offset(coords.size.width / 2f, coords.size.height / 2f)
                radius = maxOf(coords.size.width, coords.size.height) / 2f
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                // 字要靠 Clear 从卡片上挖洞：这一层必须单独离屏，否则洞会直接穿到窗口背后
                compositingStrategy = CompositingStrategy.Offscreen
                clip = true
                this.shape = shape
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onClick(center, radius) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 卡片本体：白色只当 DstIn 的遮罩，底色由品牌混色采样补上
        Box(
            Modifier
                .matchParentSize()
                .then(
                    if (backdrop != null) {
                        Modifier.textureBlur(
                            backdrop = backdrop,
                            shape = shape,
                            blurRadius = 150f,
                            noiseCoefficient = BlurDefaults.NoiseCoefficient,
                            colors = BlurDefaults.blurColors(blendColors = blend),
                            contentBlendMode = BlendMode.DstIn,
                        )
                    } else {
                        Modifier
                    },
                )
                .background(if (backdrop != null) Color.White else OnPaper),
        )
        // 字只负责挖洞：DstOut 按字的 alpha 把卡片擦成透明（Clear 会把整块矩形抹掉，
        // 连字之间的空隙一起没了），颜色取什么都无所谓
        Text(
            label,
            modifier = Modifier.graphicsLayer { blendMode = BlendMode.DstOut },
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = if (backdrop != null) Color(0xCC000000) else if (onDark) OnPaper else Color(0xCC000000),
        )
    }
}
