package com.xkspeed.systemuimount.ui

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xkspeed.systemuimount.core.CrashLog
import com.xkspeed.systemuimount.core.DaemonService
import com.xkspeed.systemuimount.core.Module
import com.xkspeed.systemuimount.core.ModuleState
import com.xkspeed.systemuimount.core.ApkStore
import com.xkspeed.systemuimount.core.LogConfig
import com.xkspeed.systemuimount.core.Root
import com.xkspeed.systemuimount.core.Update
import com.xkspeed.systemuimount.core.UpdateInfo
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as kyantRememberLayerBackdrop
import com.xkspeed.systemuimount.ui.component.glass.GlassBottomNav
import com.xkspeed.systemuimount.ui.component.glass.GlassNavItem
import com.xkspeed.systemuimount.ui.components.MiuixDialog
import com.xkspeed.systemuimount.ui.util.LocalIsWideScreen
import com.xkspeed.systemuimount.ui.util.shouldShowSplitPane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Settings
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Main : Route

    @Serializable
    data object Logs : Route

    @Serializable
    data object UiSettings : Route

    @Serializable
    data object VolumeCheck : Route

    @Serializable
    data object CrashLogs : Route

    @Serializable
    data object ThirdPartyLicenses : Route

    @Serializable
    data object About : Route

    /** 从设置里重新看一遍引导 */
    @Serializable
    data object Guide : Route
}

/** 底栏三页 默认停在中间的主页 */
private const val HOME_PAGE = 1

@Composable
fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 首次启动先走引导：欢迎 / 须知 / 环境 / 音量键 / 完成
    // 校验收在这里做 装完模块第一次打开就能确认按键读得到
    var onboarded by remember { mutableStateOf(UiConfigState.onboarded) }
    if (!onboarded) {
        OnboardingPage(
            onDone = {
                UiConfigState.onboarded = true
                UiConfigState.save(context)
                onboarded = true
            },
        )
        return
    }

    // 返回（含预测性返回手势）由 NavDisplay 内部统一处理 这里不再注册 handler：
    // 两层 handler 会互相抢事件 反而让手势失效
    val backStack = rememberNavBackStack<Route>(Route.Main)

    // 连点两下同一个入口会往栈里塞两条一模一样的路由
    // NavDisplay 随即抛 Duplicate contentKey 整个界面崩掉 这里拦住
    fun push(route: Route) {
        if (backStack.lastOrNull() != route) backStack.add(route)
    }
    var state by remember { mutableStateOf(ModuleState()) }
    var rootOk by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var busyTitle by remember { mutableStateOf<String?>(null) }
    var busySub by remember { mutableStateOf("") }
    var showRootDialog by remember { mutableStateOf(false) }
    var crashText by remember { mutableStateOf<String?>(null) }
    // 记录当前弹窗对应的是哪份崩溃文件 关闭时用来标记「已看过」
    var crashFile by remember { mutableStateOf<File?>(null) }

    // 主页选到了系统界面本体：拦下来并引导去界面专区
    var sysUiInMain by remember { mutableStateOf(false) }
    // 让底栏跳到指定页（-1 表示无）
    var jumpTab by remember { mutableIntStateOf(-1) }

    // 更新：启动时后台查一次 10 秒超时 查不到就当没更新
    var latest by remember { mutableStateOf<UpdateInfo?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    fun openRelease() {
        val url = latest?.url?.takeIf { it.isNotBlank() }
            ?: "https://github.com/XKspeed/SystemUIRescue/releases"
        runCatching { uriHandler.openUri(url) }
    }

    fun checkUpdate(manual: Boolean) {
        if (checkingUpdate) return
        scope.launch {
            checkingUpdate = true
            val info = Update.check(10_000)
            checkingUpdate = false
            if (info == null) {
                // 超时/断网：手动点才提示 启动那次安静失败
                if (manual) toast(context, "检查更新失败 网络不通")
                return@launch
            }
            if (Update.isNewer(info)) {
                latest = info
                toast(context, "发现 v${Update.label(info)} 更新")
            } else {
                latest = null
                if (manual) toast(context, "已是最新版本 v${Update.currentName}")
            }
        }
    }

    // 抓取配置存在 App 自己这边 进界面先读一次
    LaunchedEffect(Unit) { LogConfig.load(context) }

    LaunchedEffect(Unit) {
        if (UiConfigState.autoCheckUpdate) checkUpdate(manual = false)
    }

    // 关闭启动崩溃弹窗：标记这份崩溃已展示过 下次启动不再弹（只提醒一次）
    fun dismissCrash() {
        crashText = null
        val f = crashFile
        crashFile = null
        if (f != null) CrashLog.markCrashShown(f)
    }

    // 启动时先看上次有没有崩：把堆栈直接摊给用户看
    // 不用去抓 logcat（没 root 抓不到） 同一份崩溃只弹一次
    LaunchedEffect(Unit) {
        val file = CrashLog.pendingCrash()
        if (file != null) {
            CrashLog.log("启动", "检测到上次崩溃记录 长度 ${file.length()}")
            crashText = file.readText()
            crashFile = file
        } else {
            // 能走到这里说明这次启动没崩（或崩溃已展示过） 清掉连续计数
            CrashLog.markAlive()
        }
    }

    // 启动时拿不到 root 就直接提示 别让用户点进一堆"操作失败"
    LaunchedEffect(rootOk) {
        if (rootOk == false) showRootDialog = true
    }

    suspend fun refresh() {
        state = Module.readState()
    }

    // 首次进入：先要 root 再读状态
    LaunchedEffect(Unit) {
        rootOk = Root.available()
        if (rootOk == true) runCatching { refresh() }
    }

    // 守护运行中定时刷新（PID / 救砖次数会变）
    LaunchedEffect(state.daemonRunning) {
        while (state.daemonRunning) {
            delay(2000)
            runCatching { refresh() }
        }
    }

    // 守护状态与前台服务保持一致：运行就挂服务 停了就撤 避免进程被冻结
    LaunchedEffect(state.daemonRunning) {
        if (state.daemonRunning) DaemonService.start(context) else DaemonService.stop(context)
    }

    // 选文件：固定拉 MT 管理器 不再弹来源选择
    var pendingPick by remember { mutableStateOf<PickerTarget?>(null) }

    fun applyPicked(target: PickerTarget, path: String) {
        scope.launch {
            if (target == PickerTarget.RESCUE) {
                // 复制到模块根并统一改名 MiuiSystemUI.apk
                busy = true
                busyTitle = "救砖界面至模块根目录"
                busySub = "正在复制…"
                val out = Module.prepare(path)
                if (out.contains("OK")) {
                    busySub = "已完成"
                    runCatching { refresh() }
                } else {
                    busyTitle = "复制失败"
                    busySub = firstLine(out)
                }
                delay(600)
                busy = false
                busyTitle = null
            } else {
                Module.setConf("TARGET_APK_PATH", path)
                runCatching { refresh() }
                toast(context, "已选择待测界面")
            }
        }
    }

    fun handlePicked(target: PickerTarget, uri: Uri) {
        // 复制动辄上百兆 不能压在主线线程上 否则选择器刚回来就 ANR
        scope.launch {
            val file = withContext(Dispatchers.IO) { copyPickedFile(context, uri) }
            if (file == null) {
                toast(context, "读取失败 这个文件管理器给的地址打不开 换一个再试")
                return@launch
            }
            if (!file.looksLikeApk()) {
                withContext(Dispatchers.IO) { file.delete() }
                toast(context, "不是 apk 请选择后缀为 apk 的安装包")
                return@launch
            }

            // 主页：不校验包名（测的可能根本不是 SystemUI）
            // 只拦两件事 —— 待测 apk 和救砖 apk 是同一个文件、待测 apk 是系统界面本体
            if (target == PickerTarget.MAIN_RESCUE || target == PickerTarget.MAIN_TARGET) {
                val isRescue = target == PickerTarget.MAIN_RESCUE
                // 主页的待测 apk 是直接 pm install 装进系统的
                // 系统界面本体装错一次就是黑砖 这里必须拉黑 只放行界面专区的模块流程
                if (!isRescue) {
                    val pkg = withContext(Dispatchers.IO) { packageNameOf(context, file) }
                    if (pkg == SYSTEMUI_PACKAGE) {
                        withContext(Dispatchers.IO) { file.delete() }
                        sysUiInMain = true
                        return@launch
                    }
                }
                // 收进私有目录 只有重新选才会覆盖旧的
                val dst = withContext(Dispatchers.IO) {
                    ApkStore.import(
                        context,
                        file,
                        if (isRescue) ApkStore.RESCUE else ApkStore.TARGET,
                    ).also { file.delete() }
                }
                if (dst == null) {
                    toast(context, "保存失败 存储空间可能不够")
                    return@launch
                }
                val other = if (isRescue) LogConfig.targetPath else LogConfig.rescuePath
                val same = withContext(Dispatchers.IO) {
                    ApkStore.sameContent(dst.absolutePath, other)
                }
                if (same) {
                    // 两份一样 崩了装回去等于什么都没换
                    withContext(Dispatchers.IO) { dst.delete() }
                    if (isRescue) {
                        LogConfig.rescuePath = ""
                        LogConfig.rescueSize = 0L
                    } else {
                        LogConfig.targetPath = ""
                        LogConfig.targetSize = 0L
                    }
                    LogConfig.save(context)
                    toast(context, "待测apk不能和救砖apk是同一个文件")
                    return@launch
                }
                if (isRescue) {
                    LogConfig.rescuePath = dst.absolutePath
                    LogConfig.rescueSize = dst.length()
                    toast(context, "已选择救砖apk")
                } else {
                    LogConfig.targetPath = dst.absolutePath
                    LogConfig.targetSize = dst.length()
                    toast(context, "已选择待测apk")
                }
                LogConfig.save(context)
                return@launch
            }

            // 界面页：装错界面救砖流程直接废掉 这里必须拦住
            val pkg = withContext(Dispatchers.IO) { packageNameOf(context, file) }
            if (pkg != SYSTEMUI_PACKAGE) {
                withContext(Dispatchers.IO) { file.delete() }
                toast(
                    context,
                    if (pkg == null) "这个文件不是有效的 apk" else "包名是 $pkg 不是系统界面",
                )
                return@launch
            }
            applyPicked(target, file.absolutePath)
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val target = pendingPick
        pendingPick = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null && target != null) {
            handlePicked(target, uri)
        }
    }

    /** 固定走 MT 管理器 设备上正版优先 没有再退共存版 */
    fun pickFile(target: PickerTarget) {
        val source = queryMtPickers(context).firstOrNull()
        if (source == null) {
            toast(context, "未找到mt管理器 你连这个都没有吗")
            return
        }
        pendingPick = target
        runCatching { pickLauncher.launch(buildSourcePickIntent(source.component)) }
            .onFailure {
                pendingPick = null
                toast(context, "打不开 ${source.label}")
            }
    }

    // Overlay 版下拉要靠 Scaffold 提供 popup host（Sonify 也是这么挂的）
    // 这里不挂 topBar/bottomBar 页面自己管 insets 传进来的 padding 忽略掉
    Scaffold(containerColor = Color.Transparent) { _ ->
        NavDisplay(
            backStack = backStack,
            // 转场用 MiuixDefault 圆角裁剪直接读系统圆角 和 LiftArc 一致
            // 返回（含预测性返回手势）全交给 NavDisplay 自己接管
            transition = NavTransitions.MiuixDefault,
            effects = NavDisplayEffects(
                cornerClipRadius = rememberNavSystemCornerRadius(),
                backdropColor = MiuixTheme.colorScheme.surface,
            ),
        ) {
            entry<Route.Main> {
                MainScaffold(
                    state = state,
                    rootOk = rootOk,
                    busy = busy,
                    jumpTab = jumpTab,
                    onJumpTabConsumed = { jumpTab = -1 },
                    onPickRescue = { pickFile(PickerTarget.RESCUE) },
                    onPickTarget = { pickFile(PickerTarget.TARGET) },
                    onPickMainRescue = { pickFile(PickerTarget.MAIN_RESCUE) },
                    onPickMainTarget = { pickFile(PickerTarget.MAIN_TARGET) },
                    onStart = start@{
                        val target = state.targetPath
                        if (target.isBlank()) {
                            toast(context, "请先选择待测界面")
                            return@start
                        }
                        busy = true
                        busyTitle = "启动测试"
                        busySub = "正在准备救砖文件…"
                        scope.launch {
                            val out = Module.testRun(target)
                            if (out.contains("OK")) {
                                busySub = "已启动"
                                runCatching { refresh() }
                                DaemonService.start(context)
                            } else {
                                busyTitle = "启动失败"
                                busySub = firstLine(out)
                            }
                            delay(700)
                            busy = false
                            busyTitle = null
                        }
                    },
                    onStop = {
                        busy = true
                        busyTitle = "停止守护"
                        busySub = "正在结束进程…"
                        scope.launch {
                            val out = Module.stop()
                            runCatching { refresh() }
                            DaemonService.stop(context)
                            busyTitle = null
                            busy = false
                            toast(context, firstLine(out).ifBlank { "已停止" })
                        }
                    },
                    onOpenLogs = { push(Route.Logs) },
                    onOpenUiSettings = { push(Route.UiSettings) },
                    onOpenVolumeCheck = { push(Route.VolumeCheck) },
                    onOpenCrashLog = { push(Route.CrashLogs) },
                    onOpenThirdPartyLicenses = { push(Route.ThirdPartyLicenses) },
                    onOpenAbout = { push(Route.About) },
                    onOpenGuide = { push(Route.Guide) },
                    latest = latest,
                    onOpenRelease = { openRelease() },
                    onCheckUpdate = {
                        // 点了至少要有反应 否则像没按到
                        toast(context, "正在检查更新…")
                        checkUpdate(manual = true)
                    },
                    checkingUpdate = checkingUpdate,
                    onSetConf = { k, v ->
                        scope.launch {
                            Module.setConf(k, v)
                            runCatching { refresh() }
                        }
                    },
                    onRescueToggle = { off ->
                        scope.launch {
                            val out = Module.setRescueOff(off)
                            runCatching { refresh() }
                            if (!out.contains("OK")) toast(context, firstLine(out))
                        }
                    },
                    onSetCrashLimit = { limit ->
                        scope.launch {
                            val out = Module.setCrashLimit(limit)
                            runCatching { refresh() }
                            if (!out.contains("OK")) toast(context, firstLine(out))
                        }
                    },
                )
            }

            entry<Route.Logs> {
                LogPage(
                    title = "调试日志",
                    autoRefresh = state.debug,
                    loader = { lines -> Module.tailDebug(lines) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }

            entry<Route.UiSettings> {
                UiSettingsPage(onBack = { backStack.removeLastOrNull() })
            }

            entry<Route.VolumeCheck> {
                VolumeCheckPage(
                    onBack = { backStack.removeLastOrNull() },
                    onFinished = { scope.launch { runCatching { refresh() } } },
                )
            }

            entry<Route.CrashLogs> {
                CrashLogPage(onBack = { backStack.removeLastOrNull() })
            }

            entry<Route.ThirdPartyLicenses> {
                ThirdPartyLicensesPage(onBack = { backStack.removeLastOrNull() })
            }

            entry<Route.About> {
                AboutPage(
                    state = state,
                    latest = latest,
                    onOpenRelease = { openRelease() },
                    onOpenThirdPartyLicenses = { push(Route.ThirdPartyLicenses) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }

            entry<Route.Guide> {
                OnboardingPage(
                    onDone = {
                        // 引导里可能重新校验了音量键 回来顺手刷一次状态
                        scope.launch { runCatching { refresh() } }
                        backStack.removeLastOrNull()
                    },
                )
            }

        }
    }

    // 关掉预测性返回预览时把返回事件拦在 NavDisplay 前面：
    // 手势拖动期间页面完全不跟手（等于把手势进度上限压成 0）
    // 松手提交后才 pop 播放普通返回动画
    val noPreviewBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = noPreviewBackState,
        isBackEnabled = !UiConfigState.predictiveBack && backStack.size > 1,
        onBackCompleted = { backStack.removeLastOrNull() },
    )

    // 等待框：复制几十兆 APK 时要看得见状态 且不允许误触关闭
    busyTitle?.let { title ->
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
                shape = RoundedCornerShape(22.dp),
                color = MiuixTheme.colorScheme.surface,
            ) {
                Column(
                    Modifier.padding(horizontal = 24.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        title,
                        style = MiuixTheme.textStyles.title2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    if (busySub.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            busySub,
                            style = MiuixTheme.textStyles.subtitle,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }

    // 上次崩溃的现场：直接把堆栈摊出来 用户复制走就能贴给我
    crashText?.let { text ->
        MiuixDialog(
            title = "上次启动时崩溃了",
            onDismiss = { dismissCrash() },
        ) {
            if (UiConfigState.safeMode) {
                Text(
                    "已连续崩溃多次 已自动关闭玻璃效果（安全模式）",
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.subtitle,
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                text.take(1200),
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.subtitle,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 18,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "文件位置：" + (CrashLog.externalCrashFile()?.absolutePath
                    ?: CrashLog.lastCrashFile()?.absolutePath ?: "未知"),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = { dismissCrash() }) { Text("关闭") }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        cm?.setPrimaryClip(
                            android.content.ClipData.newPlainText("crash", text)
                        )
                        toast(context, "已复制堆栈")
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text("复制堆栈") }
            }
        }
    }

    // 主页选了系统界面本体：这里装了就是黑砖 引导去走模块流程
    if (sysUiInMain) {
        MiuixDialog(
            title = "这个包不能装",
            onDismiss = { sysUiInMain = false },
        ) {
            Text(
                "$SYSTEMUI_PACKAGE 是系统界面本体。主页会把待测包直接装进系统，" +
                    "装坏一次就是黑砖，所以这里不让选。",
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.subtitle,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "要测系统界面请去「界面专区」 那边走模块挂载和救砖守护 " +
                    "崩了会自动换回救砖包",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = { sysUiInMain = false }) { Text("知道了") }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = {
                        sysUiInMain = false
                        jumpTab = 0
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text("前往界面专区") }
            }
        }
    }

    // 没有 root：直接给两个选择 别让用户点进一堆"操作失败"
    if (showRootDialog) {
        MiuixDialog(
            title = "需要 ROOT 权限",
            onDismiss = { showRootDialog = false },
        ) {
            Text(
                "本应用通过 su 调用脚本读取配置 没有 root 就改不了运行模式 " +
                    "也起不了救砖守护",
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.subtitle,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "请在 su 授权框里选「允许」后重新打开；" +
                    "如果之前点过拒绝 去 root 管理器的授权列表里改回来",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = { showRootDialog = false }) { Text("忽略") }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { (context as? android.app.Activity)?.finish() },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text("退出") }
            }
        }
    }

}

@Composable
private fun MainScaffold(
    state: ModuleState,
    rootOk: Boolean?,
    busy: Boolean,
    jumpTab: Int,
    onJumpTabConsumed: () -> Unit,
    onPickRescue: () -> Unit,
    onPickTarget: () -> Unit,
    onPickMainRescue: () -> Unit,
    onPickMainTarget: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenUiSettings: () -> Unit,
    onOpenVolumeCheck: () -> Unit,
    onOpenCrashLog: () -> Unit,
    onOpenThirdPartyLicenses: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenGuide: () -> Unit,
    latest: UpdateInfo?,
    onOpenRelease: () -> Unit,
    onCheckUpdate: () -> Unit,
    checkingUpdate: Boolean,
    onSetConf: (String, String) -> Unit,
    onRescueToggle: (Boolean) -> Unit,
    onSetCrashLimit: (Int) -> Unit,
) {
    // 三页：界面 / 主页 / 设置 默认落在中间的主页
    val pagerState = rememberPagerState(initialPage = HOME_PAGE, pageCount = { 3 })
    var selected by remember { mutableIntStateOf(HOME_PAGE) }
    val navItems = remember {
        listOf(
            GlassNavItem("界面", MiuixIcons.Layers),
            GlassNavItem("主页", MiuixIcons.Home),
            GlassNavItem("设置", MiuixIcons.Settings),
        )
    }
    val scope = rememberCoroutineScope()

    // 渲染阶段埋点：崩了能知道是第几个 tab / 哪一层出的问题
    LaunchedEffect(selected) {
        CrashLog.stage("主界面 tab=${listOf("界面", "主页", "设置").getOrElse(selected) { "?" }}")
    }

    // 用 settledPage（动画结束后的落点）而不是 currentPage：
    // 底栏从「界面」点到「设置」时 currentPage 中途会路过 1（主页）
    // 若用它同步 selected 底栏会在动画中途跳到「主页」
    // 反过来又触发一次 animateScrollToPage(1) —— 结果永远停在中间页
    LaunchedEffect(pagerState.settledPage) {
        selected = pagerState.settledPage
    }

    // 主页误选系统界面包被拦下后 点弹窗里的「前往界面专区」直接切过去
    LaunchedEffect(jumpTab) {
        if (jumpTab < 0) return@LaunchedEffect
        pagerState.animateScrollToPage(jumpTab)
        onJumpTabConsumed()
    }

    // 系统返回键：不在主页时按返回 → 回到主页 而不是退出应用
    // （底栏三页是同一个 pager 不是三条路由 所以要在这一层自己吃掉返回）
    val mainBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = mainBackState,
        isBackEnabled = pagerState.currentPage != HOME_PAGE,
        onBackCompleted = { scope.launch { pagerState.animateScrollToPage(HOME_PAGE) } },
    )

    // 底栏采样源：kyant backdrop 采样整个页面内容（含滚动中的列表）
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = kyantRememberLayerBackdrop()
    CompositionLocalProvider(LocalIsWideScreen provides shouldShowSplitPane()) {
        // 底栏是浮在内容上的（内容延伸到它后面 玻璃采样才有东西）
        // 所以不走 Scaffold 的 bottomBar —— 那会把内容顶开 玻璃后面就是空背景
        val pagePadding = PaddingValues(
            bottom = WindowInsets.navigationBars.asPaddingValues()
                .calculateBottomPadding() + 70.dp,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(surfaceColor),
        ) {
            // 采样层只包住页面内容 底栏必须放在这个 Box 之外 ——
            // 否则底栏自己被录进它要采样的那一层 形成自采样递归 绘制时直接崩
            // （Sonify 的 NavPageVisual / NavBottomVisual 同样是两个平级节点 ）
            Box(
                Modifier
                    .fillMaxSize()
                    .kyantLayerBackdrop(backdrop),
            ) {
                // 只认底栏切页：左右滑动会把「主页」误带到设置/界面
                // userScrollEnabled=false 只关手势 底栏的 animateScrollToPage 照常走
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    userScrollEnabled = false,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (page) {
                        // 界面：选救砖 / 待测 与启动测试
                        0 -> HomePage(
                            padding = pagePadding,
                            state = state,
                            rootOk = rootOk,
                            busy = busy,
                            onPickRescue = onPickRescue,
                            onPickTarget = onPickTarget,
                            onStart = onStart,
                            onStop = onStop,
                        )

                        // 主页：自己的测试脚本 自带开抓与救砖 不碰模块守护
                        1 -> MainPage(
                            padding = pagePadding,
                            state = state,
                            rootOk = rootOk,
                            latest = latest,
                            onOpenRelease = onOpenRelease,
                            onPickRescue = onPickMainRescue,
                            onPickTarget = onPickMainTarget,
                        )

                        // 设置：运行 / 自动救砖 / 外观 / 调试 / 关于入口
                        else -> SettingsPage(
                            padding = pagePadding,
                            state = state,
                            onSetConf = onSetConf,
                            onRescueToggle = onRescueToggle,
                            onSetCrashLimit = onSetCrashLimit,
                            onOpenUiSettings = onOpenUiSettings,
                            onOpenDebugLog = onOpenLogs,
                            onOpenCrashLog = onOpenCrashLog,
                            onOpenVolumeCheck = onOpenVolumeCheck,
                            onOpenAbout = onOpenAbout,
                            onOpenGuide = onOpenGuide,
                            onCheckUpdate = onCheckUpdate,
                            checkingUpdate = checkingUpdate,
                        )
                    }
                }
            }

            // 底栏浮层：垫在导航栏之上、宽度照原版收窄到 93%
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                GlassBottomNav(
                    selectedIndex = { selected },
                    onTabSelected = { idx ->
                        selected = idx
                        scope.launch { pagerState.animateScrollToPage(idx) }
                    },
                    items = navItems,
                    backdrop = backdrop,
                )
            }

        }
    }
}

/** webctl 的输出首行去掉 FAIL 前缀 拿来当提示语 */
private fun firstLine(out: String): String =
    out.lineSequence().firstOrNull { it.isNotBlank() }
        ?.removePrefix("FAIL")?.trim().orEmpty()

private fun toast(context: android.content.Context, msg: String) {
    if (msg.isNotBlank()) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
