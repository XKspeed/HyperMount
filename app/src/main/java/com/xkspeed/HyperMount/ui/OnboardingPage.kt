package com.xkspeed.HyperMount.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.xkspeed.HyperMount.core.Module
import com.xkspeed.HyperMount.core.ModuleState
import com.xkspeed.HyperMount.core.Root
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 引导总步数：欢迎 / 须知 / 环境 / 音量键 / 完成 */
private const val ONBOARD_STEPS = 5

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

/**
 * 首次启动引导
 *
 * 流程照着 HyperCeiler 的 provision 走（欢迎 → 须知 → 环境/权限 → 基础项 → 完成），
 * 去掉了它那套联网协议与验证码；基础项换成对救砖最要紧的音量键校验：
 * 救砖靠音量键确认 装完模块第一次打开就该确认按键能读到，
 * 而不是等真崩了才发现按了没反应。
 *
 * 全程不写 any 配置，只有音量键校验通过才落盘 VOLUME_CHECK_ASSESS=1。
 */
@Composable
fun OnboardingPage(
    onDone: () -> Unit,
    /** 直接从音量键这一步开始（系统版本变了要重验时用） */
    startAtVolume: Boolean = false,
    /** 强制校验：忽略已校验标记 作废模块侧结果后重按一遍 */
    forceVolume: Boolean = false,
    /** 从设置里回看：允许随时退出 首次引导不给退 */
    allowExit: Boolean = false,
) {
    val context = LocalContext.current

    var step by remember { mutableIntStateOf(if (startAtVolume) 3 else 0) }
    var agreed by remember { mutableStateOf(false) }

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
                .toBitmap(168, 168)
                .asImageBitmap()
        }.getOrNull()
    }

    // 进环境页就检查一遍 root / 模块 / SystemUI
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
            volHint = "之前已经校验过 直接继续就行"
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
            volHint = "没有 ROOT 权限 读不到按键设备 先去授权再来"
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
        volHint = "请按一次【音量 +】"
        val up = runCatching { Module.check("up") }.getOrDefault("").trim()
        val upLine = up.lineSequence().firstOrNull { it.startsWith("OK") }
        if (upLine == null) {
            upState = KeyState.Failed
            volHint = "没读到音量 + 确认键没坏 点下面的重试再来一次"
            return@LaunchedEffect
        }
        upState = KeyState.Passed
        val upDev = upLine.removePrefix("OK").trim()

        downState = KeyState.Waiting
        volHint = "请按一次【音量 −】"
        val down = runCatching { Module.check("down") }.getOrDefault("").trim()
        val downLine = down.lineSequence().firstOrNull { it.startsWith("OK") }
        if (downLine == null) {
            downState = KeyState.Failed
            volHint = "音量 + 通过了 音量 − 没读到 再试一次"
            return@LaunchedEffect
        }
        downState = KeyState.Passed
        val downDev = downLine.removePrefix("OK").trim()

        // 两个都过才写配置 记下实测设备 救砖时不用再猜
        val save = runCatching { Module.saveVolumeCheck(upDev, downDev) }.getOrDefault("")
        if (save.contains("OK")) {
            // 记下这次校验时的系统版本 下次系统升级才会再拉一次
            UiConfigState.markVolumeChecked(context)
            volHint = "校验通过 已写入模块配置"
        } else {
            volHint = "按键能读到 但写入配置失败 去设置里再校验一次"
        }
    }

    val volDone = upState is KeyState.Passed && downState is KeyState.Passed
    // 环境这两项缺一不可：没有 ROOT 或不是 XKspeed 的模块 后面所有操作都会失败
    // 与其放进去点一路报错 不如卡在这一页把话说清楚
    val envReady = env.root && env.moduleOk
    val canNext = when (step) {
        1 -> agreed
        2 -> !envChecking && envReady
        else -> true
    }
    val buttonLabel = when (step) {
        0 -> "开始"
        1 -> "同意并继续"
        2 -> when {
            envChecking -> "检测中…"
            !envReady -> "等待环境检查通过"
            else -> "继续"
        }
        3 -> if (volDone) "继续" else "跳过校验"
        else -> "开始使用"
    }

    fun next() {
        // 环境没过 后面几步都不该放行
        if (step == 2 && (!envReady || envChecking)) return
        when (step) {
            3 -> if (!volDone) volSkip = true
            4 -> {
                onDone()
                return
            }
        }
        if (step < ONBOARD_STEPS - 1) step++
    }

    // 首次引导只处理步骤回退 第 1 步之前不给退（退了也进不了主界面）
    // 从设置进来时第 1 步按返回等同于退出引导
    BackHandler(enabled = step > 0 || allowExit) {
        when {
            step > 0 -> {
                if (step == 3 && !volDone) volSkip = true
                step--
            }
            else -> onDone()
        }
    }

    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // 顶部：步骤条（首次引导没有「跳过」 只有从设置进来才给退出）
            Row(
                Modifier.fillMaxWidth()
                    .padding(start = 24.dp, end = if (allowExit) 10.dp else 24.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    repeat(ONBOARD_STEPS) { i ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(
                                    if (i <= step) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.surfaceContainer
                                )
                        )
                    }
                }
                if (allowExit) {
                    Spacer(Modifier.width(14.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onDone() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "退出",
                            style = MiuixTheme.textStyles.subtitle,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    }
                }
            }

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { it / 4 * dir } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 4 * dir } + fadeOut())
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "onboarding",
            ) { index ->
                when (index) {
                    0 -> WelcomeStep(appIcon)
                    1 -> NoticeStep(agreed) { agreed = it }
                    2 -> EnvStep(env, envChecking) { envRetry++ }
                    3 -> VolumeStep(upState, downState, volHint, canSkip = !volAlready) {
                        volSkip = false
                        volRetry++
                    }
                    else -> DoneStep()
                }
            }

            // 底部主按钮
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            ) {
                Button(
                    onClick = { next() },
                    enabled = canNext,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    minHeight = 52.dp,
                ) {
                    Text(buttonLabel, fontSize = 16.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "第 ${step + 1} / $ONBOARD_STEPS 步",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

// ---------------- 第 1 页：欢迎 ----------------

@Composable
private fun WelcomeStep(icon: androidx.compose.ui.graphics.ImageBitmap?) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Box(
            Modifier
                .size(92.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(76.dp).clip(RoundedCornerShape(18.dp)),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(
                    imageVector = MiuixIcons.Layers,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(22.dp))
        Text(
            "HyperMount",
            style = MiuixTheme.textStyles.title1,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "v${com.xkspeed.HyperMount.core.Update.currentName}",
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "系统界面崩了 也能自己换回来",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(34.dp))

        FeatureRow(
            icon = MiuixIcons.VolumeUp,
            title = "崩溃自动救砖",
            desc = "crash 日志一超阈值 立刻装回救砖包并重启界面",
        )
        FeatureRow(
            icon = MiuixIcons.Info,
            title = "音量键手动救砖",
            desc = "屏幕没反应也能按音量键确认换包",
        )
        FeatureRow(
            icon = MiuixIcons.Backup,
            title = "日志留档",
            desc = "崩溃现场保存在手机里 方便回头定位问题",
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.main)
            Spacer(Modifier.height(2.dp))
            Text(
                desc,
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

// ---------------- 第 2 页：使用须知 ----------------

@Composable
private fun NoticeStep(agreed: Boolean, onAgreed: (Boolean) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(30.dp))
        Text("使用之前", style = MiuixTheme.textStyles.title1, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "这个 App 不联网上传任何东西 它只是替你调用模块里的脚本",
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(26.dp))

        NoticeItem(
            "需要 ROOT 与模块",
            "所有操作都走 root shell 调用 systemui_mount 的 webctl.sh 和 check.sh。" +
                "没装模块时只能看不能救。",
        )
        NoticeItem(
            "救砖包必须能用",
            "换回去的包得是能正常启动的系统界面。如果救砖包本身也是坏的 换回去照样黑屏。",
        )
        NoticeItem(
            "出问题自己负责",
            "改系统界面有风险。刷机、模块冲突导致的后果需要你自己承担。",
        )

        Spacer(Modifier.height(18.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onAgreed(!agreed) }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                state = if (agreed) ToggleableState.On else ToggleableState.Off,
                onClick = { onAgreed(!agreed) },
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "我已阅读并了解以上内容",
                style = MiuixTheme.textStyles.main,
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun NoticeItem(title: String, desc: String) {
    Column(Modifier.padding(vertical = 9.dp)) {
        Text(title, style = MiuixTheme.textStyles.main, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(3.dp))
        Text(
            desc,
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

// ---------------- 第 3 页：环境检查 ----------------

@Composable
private fun EnvStep(env: EnvResult, checking: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(30.dp))
        Text("环境检查", style = MiuixTheme.textStyles.title1, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "确认下面两项都能读到 后面救砖才有意义",
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(26.dp))

        Card(Modifier.fillMaxWidth()) {
            EnvRow("ROOT 权限", if (checking) "检测中…" else if (env.root) "已授权" else "未授权", env.root, checking)
            EnvRow(
                "救砖模块",
                when {
                    checking -> "检测中…"
                    env.moduleVersion.isBlank() -> "未安装"
                    !env.moduleOk -> "不是 XKspeed 版"
                    else -> "v${env.moduleVersion} · XKspeed"
                },
                env.moduleOk,
                checking,
            )
        }

        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .clickable(enabled = !checking) { onRetry() }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                if (checking) "正在检测…" else "重新检测",
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(8.dp))
        if (!checking && !(env.root && env.moduleOk)) {
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.error.copy(alpha = 0.08f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    "两项都通过才能继续",
                    style = MiuixTheme.textStyles.main,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        !env.root -> "先在 ROOT 管理器里给 HyperMount 授权 然后点上面的「重新检测」。"
                        env.moduleVersion.isBlank() -> "还没装救砖模块 去 Magisk / KernelSU 里刷入模块 重启后再回来检测。"
                        else -> "当前模块不是 XKspeed 版 它的脚本对不上 请换成官方发布的那份。"
                    },
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "模块作者必须是 XKspeed 用的才是这套脚本 别家挂载的调不了 sh。",
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun EnvRow(label: String, value: String, ok: Boolean, checking: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
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
    canSkip: Boolean,
    onRetry: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(30.dp))
        Text("音量键校验", style = MiuixTheme.textStyles.title1, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "救砖时要按音量键确认 先确认这两颗键能读到 不然真崩了按了也没反应",
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(26.dp))

        Card(Modifier.fillMaxWidth()) {
            KeyRow("音量 +", up)
            KeyRow("音量 −", down)
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (up is KeyState.Waiting || down is KeyState.Waiting) {
                PulsingDot()
                Spacer(Modifier.width(8.dp))
            }
            Text(
                hint.ifBlank { "准备中…" },
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

        if (canSkip && up !is KeyState.Waiting && down !is KeyState.Waiting) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onRetry() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    "重新校验",
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "每个键有 10 秒时间 按一下就行。校验只确认能读到按键事件 不改系统设置。",
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun KeyRow(label: String, state: KeyState) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MiuixTheme.textStyles.main, modifier = Modifier.weight(1f))
        Text(
            when (state) {
                KeyState.Idle -> "等待"
                KeyState.Waiting -> "请按键…"
                KeyState.Passed -> "已识别"
                KeyState.Failed -> "没读到"
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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        Box(
            Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            val checkColor = MiuixTheme.colorScheme.primary
            Canvas(Modifier.size(40.dp)) {
                val w = size.width
                val h = size.height
                val stroke = Stroke(
                    width = w * 0.12f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                )
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.18f, h * 0.52f)
                    lineTo(w * 0.42f, h * 0.75f)
                    lineTo(w * 0.84f, h * 0.27f)
                }
                drawPath(path, color = checkColor, style = stroke)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("设置完毕", style = MiuixTheme.textStyles.title1, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "欢迎使用 HyperMount",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(34.dp))

        Card(Modifier.fillMaxWidth()) {
            SwitchPreference(
                checked = autoCheck,
                onCheckedChange = {
                    autoCheck = it
                    UiConfigState.autoCheckUpdate = it
                    UiConfigState.save(context)
                },
                title = "自动检查更新",
                summary = "启动时看一眼 GitHub 上有没有新版本",
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "之后可以在「设置」里重新校验音量键、改运行模式",
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
    }
}
