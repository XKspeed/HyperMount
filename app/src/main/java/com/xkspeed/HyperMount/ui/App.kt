package com.xkspeed.HyperMount.ui

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xkspeed.HyperMount.MainActivity
import com.xkspeed.HyperMount.R
import com.xkspeed.HyperMount.core.AppLog
import com.xkspeed.HyperMount.core.DaemonService
import com.xkspeed.HyperMount.core.Downloader
import com.xkspeed.HyperMount.core.Installer
import com.xkspeed.HyperMount.core.Module
import com.xkspeed.HyperMount.core.ModuleState
import com.xkspeed.HyperMount.core.ApkStore
import com.xkspeed.HyperMount.core.LogConfig
import com.xkspeed.HyperMount.core.Logcat
import com.xkspeed.HyperMount.core.Root
import com.xkspeed.HyperMount.core.Update
import com.xkspeed.HyperMount.core.UpdateChannel
import com.xkspeed.HyperMount.core.UpdateInfo
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as kyantRememberLayerBackdrop
import com.xkspeed.HyperMount.ui.component.glass.GlassBottomNav
import com.xkspeed.HyperMount.ui.component.glass.GlassNavItem
import com.xkspeed.HyperMount.ui.component.glass.StandardBottomNav
import com.xkspeed.HyperMount.ui.component.glass.StandardBottomNavHeight
import com.xkspeed.HyperMount.ui.components.MiuixDialog
import com.xkspeed.HyperMount.ui.util.LocalIsWideScreen
import com.xkspeed.HyperMount.ui.util.rememberBlurBackdrop
import com.xkspeed.HyperMount.ui.util.shouldShowSplitPane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import top.yukonga.miuix.kmp.window.WindowDialog

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Main : Route

    @Serializable
    data object UiSettings : Route

    /** 界面设置：界面页右上角齿轮进入 常驻救砖 + 救砖逻辑 */
    @Serializable
    data object InterfaceSettings : Route

    /** 配置：主页右上角三点进入 目标包名 / 抓取时长 / 日志大小判定 */
    @Serializable
    data object Config : Route

    @Serializable
    data object VolumeCheck : Route

    @Serializable
    data object Debug : Route

    @Serializable
    data object AppLogs : Route

    @Serializable
    data object Cache : Route

    @Serializable
    data object ThirdPartyLicenses : Route

    @Serializable
    data object About : Route
}

/** 底栏三页 默认停在中间的主页 */
private const val HOME_PAGE = 1

/** 主页里被拦下的系统界面本体：是待测 apk 还是救砖 apk */
private enum class SysUiBlock { Target, Rescue }

@Composable
fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 首次启动先走引导：欢迎 / 须知 / 环境 / 音量键 / 完成
    // 校验收在这里做 装完模块第一次打开就能确认按键读得到
    // 引导页自己带版本号（ONBOARD_VERSION）：内容改了就把版本 +1 老用户重开也会进来
    val onboardFresh = UiConfigState.onboardVersion != ONBOARD_VERSION
    var onboarded by remember {
        mutableStateOf(UiConfigState.onboarded && !onboardFresh)
    }
    // 系统版本变了（升级/刷机）：旧校验里的 /dev/input/eventN 不一定还在
    // 启动就直接把校验面板拉起来 同时把模块侧的 VOLUME_CHECK_ASSESS 归零
    // 引导版本变了就先完整走一遍 别从音量页半路开始
    var volumeStale by remember {
        mutableStateOf(
            UiConfigState.onboarded && !onboardFresh && UiConfigState.needsVolumeRecheck()
        )
    }
    // 调试页的「重新查看引导」：只影响本轮显示 不改 onboarded 标记
    var showOnboarding by remember { mutableStateOf(false) }
    if (!onboarded || volumeStale || showOnboarding) {
        OnboardingPage(
            startAtVolume = volumeStale,
            forceVolume = volumeStale,
            onCancel = if (showOnboarding) ({ showOnboarding = false }) else null,
            onDone = {
                UiConfigState.onboarded = true
                UiConfigState.onboardVersion = ONBOARD_VERSION
                UiConfigState.save(context)
                onboarded = true
                // 跳过也算本次已提醒过 下次启动版本仍不一致会再拉起
                volumeStale = false
                showOnboarding = false
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

    // 守护通知点进来：直接翻到应用日志 用户能看到这一轮为什么救砖
    LaunchedEffect(Unit) {
        val act = context as? Activity
        if (act?.intent?.getBooleanExtra(MainActivity.EXTRA_OPEN_LOGS, false) == true) {
            act.intent.removeExtra(MainActivity.EXTRA_OPEN_LOGS)
            push(Route.AppLogs)
        }
        MainActivity.openLogs.collect { push(Route.AppLogs) }
    }
    var state by remember { mutableStateOf(ModuleState()) }
    // 乐观更新：改配置时界面先走一步，写盘在后台跑
    // 1 秒后回读 settings.conf，实际值对不上就把界面拉回去
    var pendingConf by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val uiState = remember(state, pendingConf) {
        pendingConf.entries.fold(state) { acc, e -> acc.withConf(e.key, e.value) }
    }
    // settings.conf 的写入不能并发：连点几下会同时跑好几条 sed 互相踩。
    // 回读也要认人 —— 每次写入带一个序号，只有「我还是这个 key 的最新一次」才收尾，
    // 否则先前那次的回读会把后一次刚点的值连同 pending 一起冲掉（表现就是闪回旧值）。
    val confWriteLock = remember { Mutex() }
    val confSeq = remember { mutableMapOf<String, Int>() }

    fun nextConfSeq(key: String): Int {
        val n = (confSeq[key] ?: 0) + 1
        confSeq[key] = n
        return n
    }
    var rootOk by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var busyTitle by remember { mutableStateOf<String?>(null) }
    var busySub by remember { mutableStateOf("") }
    var showRootDialog by remember { mutableStateOf(false) }
    var crashText by remember { mutableStateOf<String?>(null) }
    // 记录当前弹窗对应的是哪份崩溃文件 关闭时用来标记「已看过」
    var crashFile by remember { mutableStateOf<File?>(null) }

    // 主页选到了系统界面本体：拦下来并引导去 SystemUI 页
    // 待测 apk 和救砖 apk 都会被 pm install 进系统 两份都得挡
    var sysUiBlock by remember { mutableStateOf<SysUiBlock?>(null) }
    // 救砖包已经写进私有目录但还没确认：取消时删掉新包 旧配置原样不动
    var pendingRescue by remember { mutableStateOf<java.io.File?>(null) }
    var pendingPrevious by remember { mutableStateOf("") }
    // 让底栏跳到指定页（-1 表示无）
    var jumpTab by remember { mutableIntStateOf(-1) }

    // 更新：先垫上次查到的结果 再后台查一次
    // 缓存这一步很关键：查到更新后断网、或者用户重启了应用，提示不能凭空消失
    // 两个来源各有一份缓存 进页面时先按当前来源垫上 切来源时再换
    val cachedInfo = remember { Update.cached(UiConfigState.updateChannel) }
    var latest by remember {
        mutableStateOf(cachedInfo?.takeIf { Update.isNewer(it, UiConfigState.updateChannel) })
    }
    // 清单原样留着：模块版本要等 state 读回来才比得了 判定放在渲染时做
    // （检查更新可能比首屏状态先回来 那时 state 还是空的）
    var manifest by remember { mutableStateOf(cachedInfo) }
    // 本地模块版本：state 还没拉回来时先用 module.prop 顶上
    var moduleVer by remember { mutableStateOf("" to 0) }
    var checkingUpdate by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    // root 读到的 state 优先；还没读到就用 propVersion 那次的结果
    val localModuleName = state.version.ifBlank { moduleVer.first }
    val localModuleCode = state.versionCode.toIntOrNull()?.takeIf { it > 0 } ?: moduleVer.second
    // 有模块新版时这里才非空 与 latest（App 新版）各自独立
    // 本地模块版本读不到（没装模块 / 还没授 root）时不判定 免得在不知道装了什么的情况下弹提示
    val moduleLatest = remember(manifest, localModuleName, localModuleCode) {
        val hasLocal = localModuleName.isNotBlank() || localModuleCode > 0
        if (hasLocal && Update.isModuleNewer(manifest, localModuleCode, localModuleName)) {
            manifest
        } else {
            null
        }
    }

    /** 下载进度：null 表示没在下，0f..1f 表示进行中（更新卡片里显示） */
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    /** 正在下的是哪一份：app / module，用来让两张卡片各自显示自己的进度 */
    var downloadKind by remember { mutableStateOf<String?>(null) }

    /**
     * 下载 App 更新包。
     *
     * 多镜像并发分段拉（谁快用谁，单段挂了换下一条），下完按清单里的 sha256 校验，
     * 然后拉起系统安装界面。3.7MB 的包通常十几秒。
     */
    fun openRelease() {
        if (downloadProgress != null) return
        val info = latest ?: return
        scope.launch {
            downloadKind = "app"
            downloadProgress = 0f
            val name = "HyperMount-${Update.label(info)}.apk"
            val dest = File(Installer.updateDir(context), name)
            val result = runCatching {
                Downloader.fetch(
                    candidates = Update.apkSources(info),
                    dest = dest,
                    sha256 = info.sha256,
                ) { done, total ->
                    if (total > 0) {
                        val p = (done.toFloat() / total).coerceIn(0f, 1f)
                        scope.launch { downloadProgress = p }
                    }
                }
            }
            downloadProgress = null
            downloadKind = null
            if (result.isSuccess) {
                if (!Installer.installApk(context, dest)) toast(context, "无法打开安装界面")
            } else {
                val why = result.exceptionOrNull()?.message ?: "网络异常"
                toast(context, "下载失败：$why")
            }
        }
    }

    /**
     * 模块只给下载，不代装。
     *
     * 刷模块要重启手机、要用户在 Root 管理器里确认，App 这边直接装上去风险太大；
     * 下到本地目录交给用户自己刷就够了。
     */
    fun downloadModule() {
        if (downloadProgress != null) return
        val info = manifest ?: return
        val sources = Update.moduleSources(info)
        if (sources.isEmpty()) {
            // 老清单没给模块直链：退回 Release 页
            runCatching { uriHandler.openUri(Update.downloadUrl("")) }
            return
        }
        scope.launch {
            downloadKind = "module"
            downloadProgress = 0f
            val ver = Update.moduleLabel(info)
            val dest = File(Installer.updateDir(context), "module-$ver.zip")
            val result = runCatching {
                Downloader.fetch(sources, dest, info.moduleSha256) { done, total ->
                    if (total > 0) {
                        val p = (done.toFloat() / total).coerceIn(0f, 1f)
                        scope.launch { downloadProgress = p }
                    }
                }
            }
            downloadProgress = null
            downloadKind = null
            if (result.isSuccess) {
                toast(context, "模块已保存：${dest.absolutePath}")
            } else {
                val why = result.exceptionOrNull()?.message ?: "网络异常"
                toast(context, "模块下载失败：$why")
            }
        }
    }

    fun checkUpdate(manual: Boolean) {
        if (checkingUpdate) return
        val channel = UiConfigState.updateChannel
        scope.launch {
            checkingUpdate = true
            // 多镜像轮询 给足时间（内部每条 3.5s 就放弃 不会死等）
            // 手动检查时 API 排最前：镜像站整片挂掉也能查到（启动那次不打 免得吃光匿名配额）
            val info = Update.check(channel, preferApi = manual)
            checkingUpdate = false
            if (info == null) {
                // 超时/断网：手动点才提示 启动那次安静失败
                // 缓存不清空：上次查到的更新继续挂在页面上 只是说明是上一次的结果
                if (manual) {
                    toast(
                        context,
                        when {
                            // 内测清单是内测工作流构建时才写的 没有就是还没发过内测包
                            channel == UpdateChannel.INTERNAL -> "内测通道暂时没有可用的包"
                            manifest != null -> "网络不通 显示上次查到的结果"
                            else -> "检查更新失败 网络不通"
                        },
                    )
                }
                return@launch
            }
            manifest = info
            // 状态还没读回来时自己读一眼 module.prop 否则模块判定会漏
            if (rootOk == true && state.version.isBlank() && moduleVer.first.isBlank()) {
                moduleVer = runCatching { Module.propVersion() }.getOrElse { "" to 0 }
            }
            val name = state.version.ifBlank { moduleVer.first }
            val code = state.versionCode.toIntOrNull()?.takeIf { it > 0 } ?: moduleVer.second
            val appNew = Update.isNewer(info, channel)
            // 本地模块版本读不到就不判 与界面那边同一个口径
            // 模块挂在 Release 里 与更新来源无关：内测通道下 info 里的模块信息
            // 是 Update 从正式清单补过来的 所以这里不再按渠道跳过
            val hasLocalModule = name.isNotBlank() || code > 0
            val moduleNew = hasLocalModule && Update.isModuleNewer(info, code, name)
            latest = if (appNew) info else null

            // App 和模块一起报：两个都有新版就一条 toast 说完 不连着弹两次
            val parts = buildList {
                // 内测包版本号可能跟手上这份一样 提示里先写「内测版」免得用户以为是重复弹窗
                if (appNew) {
                    add(
                        if (channel == UpdateChannel.INTERNAL) "内测版 v${Update.label(info)}"
                        else "App v${Update.label(info)}",
                    )
                }
                if (moduleNew) add("模块 v${Update.moduleLabel(info)}")
            }
            if (parts.isNotEmpty()) {
                toast(context, "发现 ${parts.joinToString(" · ")} 更新")
            } else if (manual) {
                toast(
                    context,
                    buildString {
                        append("已是最新 App v${Update.currentName}")
                        if (name.isNotBlank()) append(" · 模块 v$name")
                    },
                )
            }
        }
    }

    // 抓取配置存在 App 自己这边 进界面先读一次
    LaunchedEffect(Unit) {
        LogConfig.load(context)
        // 救砖只认私有目录里的副本：旧配置指向外部原文件的在这里迁进来，
        // 副本被清掉的直接作废，免得真崩了才发现包不在了
        withContext(Dispatchers.IO) { ApkStore.ensureRescue(context) }
    }

    // 启动查一次；换了更新来源也重查一次
    // 第一次安静失败 之后（切来源）手动跑 该提示就提示
    var firstCheck by remember { mutableStateOf(true) }
    LaunchedEffect(UiConfigState.updateChannel) {
        val manual = !firstCheck
        firstCheck = false
        // 换来源先把另一份缓存垫上 免得切过去还挂着上一个来源的提示
        val cachedOfChannel = Update.cached(UiConfigState.updateChannel)
        manifest = cachedOfChannel
        latest = cachedOfChannel?.takeIf { Update.isNewer(it, UiConfigState.updateChannel) }
        if (manual || UiConfigState.autoCheckUpdate) checkUpdate(manual = manual)
    }

    // 关闭启动崩溃弹窗：标记这份崩溃已展示过 下次启动不再弹（只提醒一次）
    fun dismissCrash() {
        crashText = null
        val f = crashFile
        crashFile = null
        if (f != null) AppLog.markCrashShown(f)
    }

    // 启动时先看上次有没有崩：把堆栈直接摊给用户看
    // 不用去抓 logcat（没 root 抓不到） 同一份崩溃只弹一次
    LaunchedEffect(Unit) {
        val file = AppLog.pendingCrash()
        if (file != null) {
            AppLog.log("启动", "检测到上次崩溃记录 长度 ${file.length()}")
            crashText = file.readText()
            crashFile = file
        } else {
            // 能走到这里说明这次启动没崩（或崩溃已展示过） 清掉连续计数
            AppLog.markAlive()
        }
    }

    // 启动时拿不到 root 就直接提示 别让用户点进一堆"操作失败"
    LaunchedEffect(rootOk) {
        if (rootOk == false) showRootDialog = true
    }

    suspend fun refresh() {
        state = Module.readState()
    }

    /**
     * 写一项配置：先把新值盖到界面，后台写模块，1 秒后回读校准。
     *
     * 用户点一下开关就该立刻看到它动了，而不是等 root shell 跑完；
     * 写失败或模块没吃进去时，用回读到的真实值把界面还原，只往错误日志里记一笔。
     */
    fun writeConf(key: String, value: String) {
        pendingConf = pendingConf + (key to value)
        val seq = nextConfSeq(key)
        scope.launch {
            AppLog.i("配置", "写入 $key=$value")
            val ok = confWriteLock.withLock { Module.setConf(key, value) }
            // 留出落盘时间：sed 刚写完立刻 cat 偶尔还能读到旧内容
            delay(1000)
            // 这一秒里又点过别的值：这次的回读作废 收尾交给最后一次
            if (confSeq[key] != seq) return@launch
            val fresh = runCatching { Module.readState() }.getOrNull()
            if (fresh != null) state = fresh
            pendingConf = pendingConf - key
            val base = fresh ?: state
            val expected = base.withConf(key, value).confValue(key)
            val actual = base.confValue(key)
            if (!ok || expected != actual) {
                // 界面已经按回读值还原了 不再弹 toast 打扰 只留一条错误日志
                AppLog.e(
                    "配置",
                    "写入失败 已按实际配置显示 $key 期望=$expected 实际=$actual ok=$ok",
                )
            }
        }
    }

    // 首次进入：先要 root 再读状态
    LaunchedEffect(Unit) {
        rootOk = Root.available()
        if (rootOk == true) runCatching { refresh() }
    }

    // 升级上来的老用户：模块里已经是校验过的状态 但没有版本记录
    // 补记当前版本 不然这次系统版本变化永远检测不到
    LaunchedEffect(state.volumeAssessed) {
        if (state.volumeAssessed && UiConfigState.volCheckedVersion.isBlank()) {
            UiConfigState.markVolumeChecked(context)
        }
    }

    // 守护运行中定时刷新（PID / 救砖次数会变）
    LaunchedEffect(state.daemonRunning) {
        while (state.daemonRunning) {
            delay(2000)
            runCatching { refresh() }
        }
    }

    // 守护状态与前台服务保持一致：运行就挂服务 停了就撤 避免进程被冻结
    // 测试停了不代表守护停了：全局常驻守护是独立脚本 要再问一次
    LaunchedEffect(state.daemonRunning) {
        val active = state.daemonRunning ||
            runCatching { Module.daemonStatus().active }.getOrDefault(false)
        if (active) DaemonService.start(context) else DaemonService.stop(context)
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
                writeConf("TARGET_APK_PATH", path)
                toast(context, "已选择待测界面")
            }
        }
    }

    fun handlePicked(target: PickerTarget, uri: Uri) {
        // 复制动辄上百兆 不能压在主线线程上 否则选择器刚回来就 ANR
        scope.launch {
            // 主页：待测一个字节都不复制，救砖直接落到 files/rescueapk/
            if (target == PickerTarget.MAIN_RESCUE || target == PickerTarget.MAIN_TARGET) {
                if (target == PickerTarget.MAIN_TARGET) {
                    // 只记真实路径 —— 开跑时由 root 直接装这个文件
                    val real = withContext(Dispatchers.IO) { folderPathOf(context, uri) }
                    if (real.isNullOrBlank()) {
                        toast(context, "这个管理器没给出真实路径 换 MT 的 root 模式再选一次")
                        return@launch
                    }
                    // 只读一次做校验，不留副本
                    val pkg = withContext(Dispatchers.IO) { packageOfUri(context, uri, real) }
                    if (pkg == SYSTEMUI_PACKAGE) {
                        sysUiBlock = SysUiBlock.Target
                        return@launch
                    }
                    if (pkg == null) {
                        toast(context, "这个文件不是有效的 apk")
                        return@launch
                    }
                    if (real == LogConfig.rescuePath) {
                        toast(context, "待测apk不能和救砖apk是同一个文件")
                        return@launch
                    }
                    LogConfig.targetPath = real
                    LogConfig.targetSize = Logcat.sizeOf(real)
                    // 记下包名：miui.systemui.plugin 要走增强判定
                    LogConfig.targetPkg = pkg
                    LogConfig.save(context)
                    toast(context, "已选择待测apk")
                    return@launch
                }

                // 救砖：选择器的流直接写进 files/rescueapk/，不经任何中转目录
                val previous = LogConfig.rescuePath
                    .takeIf { it.isNotBlank() && it != LogConfig.targetPath }
                    .orEmpty()
                val dst = withContext(Dispatchers.IO) {
                    ApkStore.installRescueFromUri(context, uri, previous)
                }
                if (dst == null) {
                    toast(context, "保存失败 存储空间可能不够")
                    return@launch
                }
                val pkg = withContext(Dispatchers.IO) { packageNameOf(context, dst) }
                // 系统界面本体装错一次就是黑砖 这里必须拉黑
                if (pkg == SYSTEMUI_PACKAGE || pkg == null) {
                    withContext(Dispatchers.IO) { dst.delete() }
                    LogConfig.rescuePath = ""
                    LogConfig.rescueSize = 0L
                    LogConfig.save(context)
                    if (pkg == SYSTEMUI_PACKAGE) {
                        sysUiBlock = SysUiBlock.Rescue
                    } else {
                        toast(context, "这个文件不是有效的 apk")
                    }
                    return@launch
                }
                // 和待测是同一份内容 崩了装回去等于什么都没换
                val same = withContext(Dispatchers.IO) {
                    ApkStore.sameContent(dst.absolutePath, LogConfig.targetPath)
                }
                if (same) {
                    withContext(Dispatchers.IO) { dst.delete() }
                    LogConfig.rescuePath = ""
                    LogConfig.rescueSize = 0L
                    LogConfig.save(context)
                    toast(context, "待测apk不能和救砖apk是同一个文件")
                    return@launch
                }
                // 先不落配置：等红色警告弹窗确认
                pendingPrevious = previous
                pendingRescue = dst
                return@launch
            }

            // 界面页的待测：同样不留副本 只记真实路径
            // 开跑那一刻再去找 原文件被挪走 / 删掉 就当场提示重选
            if (target == PickerTarget.TARGET) {
                val real = withContext(Dispatchers.IO) { folderPathOf(context, uri) }
                if (real.isNullOrBlank()) {
                    toast(context, "这个管理器没给出真实路径 换 MT 的 root 模式再选一次")
                    return@launch
                }
                // 只读一次做校验：不是系统界面的一律不收
                val pkg = withContext(Dispatchers.IO) { packageOfUri(context, uri, real) }
                if (pkg != SYSTEMUI_PACKAGE) {
                    toast(
                        context,
                        if (pkg == null) "这个文件不是有效的 apk" else "包名是 $pkg 不是系统界面",
                    )
                    return@launch
                }
                applyPicked(target, real)
                return@launch
            }

            // 界面页：救砖要留副本，交给模块脚本
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

            // 装错界面救砖流程直接废掉 这里必须拦住
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

    // 救砖配置的写入：主界面和「界面设置」二级页共用同一套回调
    // 原来直接写在 MainScaffold 参数里 二级页拿不到 所以提到这儿
    val setConfCb: (String, String) -> Unit = { k, v -> writeConf(k, v) }
    val rescueToggleCb: (Boolean) -> Unit = { off ->
        pendingConf = pendingConf + ("RESCUE_OFF" to if (off) "1" else "0")
        val seq = nextConfSeq("RESCUE_OFF")
        scope.launch {
            val out = confWriteLock.withLock { Module.setRescueOff(off) }
            delay(1000)
            if (confSeq["RESCUE_OFF"] != seq) return@launch
            val fresh = runCatching { Module.readState() }.getOrNull()
            if (fresh != null) state = fresh
            pendingConf = pendingConf - "RESCUE_OFF"
            // 命令本身报错才提示；只是没生效的那种 界面已按回读值还原 记日志就够
            if (!out.contains("OK")) {
                toast(context, firstLine(out))
            } else if (fresh != null && fresh.rescueOff != off) {
                AppLog.e(
                    "配置",
                    "写入失败 已按实际配置显示 RESCUE_OFF 期望=$off 实际=${fresh.rescueOff}",
                )
            }
        }
    }
    val crashLimitCb: (Int) -> Unit = { limit ->
        pendingConf = pendingConf + ("CRASH_LIMIT" to limit.toString())
        val seq = nextConfSeq("CRASH_LIMIT")
        scope.launch {
            val out = confWriteLock.withLock { Module.setCrashLimit(limit) }
            delay(1000)
            if (confSeq["CRASH_LIMIT"] != seq) return@launch
            val fresh = runCatching { Module.readState() }.getOrNull()
            if (fresh != null) state = fresh
            pendingConf = pendingConf - "CRASH_LIMIT"
            if (!out.contains("OK")) {
                toast(context, firstLine(out))
            } else if (fresh != null && fresh.crashLimit != limit.toString()) {
                AppLog.e(
                    "配置",
                    "写入失败 已按实际配置显示 CRASH_LIMIT " +
                        "期望=$limit 实际=${fresh.crashLimit}",
                )
            }
        }
    }

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
                state = uiState,
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
                    // 只记了原路径 没有副本：开跑前现找 找不到就明确说 别让脚本空跑
                    if (!java.io.File(target).exists()) {
                        toast(context, "待测apk不见了 重新选一次")
                        return@start
                    }
                    // 完全禁用时 action.sh 会直接退出，webctl 却先回了 OK
                    // 不拦住的话用户看到「已启动」但状态立刻变回未运行，根本猜不到原因
                    if (uiState.logMode == "0") {
                        toast(context, "运行模式是完全禁用 先去设置改成「日志 + 救砖」")
                        jumpTab = 2
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
                onOpenUiSettings = { push(Route.UiSettings) },
                onOpenInterfaceSettings = { push(Route.InterfaceSettings) },
                onOpenConfig = { push(Route.Config) },
                onOpenVolumeCheck = { push(Route.VolumeCheck) },
                onOpenDebug = { push(Route.Debug) },
                onOpenAppLog = { push(Route.AppLogs) },
                onOpenCache = { push(Route.Cache) },
                onOpenThirdPartyLicenses = { push(Route.ThirdPartyLicenses) },
                onOpenAbout = { push(Route.About) },
                latest = latest,
                moduleLatest = moduleLatest,
                onCheckUpdate = {
                    // 点了至少要有反应 否则像没按到
                    toast(context, "正在检查更新…")
                    checkUpdate(manual = true)
                },
                checkingUpdate = checkingUpdate,
                onSetConf = setConfCb,
                onRescueToggle = rescueToggleCb,
                onSetCrashLimit = crashLimitCb,
            )
        }

        entry<Route.Config> {
            ConfigPage(onBack = { backStack.removeLastOrNull() })
        }

        entry<Route.UiSettings> {
            UiSettingsPage(onBack = { backStack.removeLastOrNull() })
        }

        entry<Route.InterfaceSettings> {
            // 必须传 uiState：它叠了 pendingConf（刚点下去还没写盘的值）
            // 传裸 state 的话 写盘那一秒里回读到的还是旧值 连着点几次就会被甩回去
            InterfaceSettingsPage(
                state = uiState,
                onBack = { backStack.removeLastOrNull() },
                onOpenVolumeCheck = { push(Route.VolumeCheck) },
                onSetConf = setConfCb,
                onRescueToggle = rescueToggleCb,
                onSetCrashLimit = crashLimitCb,
            )
        }

        entry<Route.Debug> {
            DebugPage(
                onBack = { backStack.removeLastOrNull() },
                onOpenOnboarding = { showOnboarding = true },
            )
        }

        entry<Route.VolumeCheck> {
            VolumeCheckPage(
                onBack = { backStack.removeLastOrNull() },
                onFinished = { scope.launch { runCatching { refresh() } } },
            )
        }

        entry<Route.AppLogs> {
            AppLogPage(onBack = { backStack.removeLastOrNull() })
        }

        entry<Route.Cache> {
            CachePage(onBack = { backStack.removeLastOrNull() })
        }

        entry<Route.ThirdPartyLicenses> {
            ThirdPartyLicensesPage(onBack = { backStack.removeLastOrNull() })
        }

        entry<Route.About> {
            AboutPage(
                moduleVersion = localModuleName,
                latest = latest,
                channel = UiConfigState.updateChannel,
                moduleLatest = moduleLatest,
                appProgress = if (downloadKind == "app") downloadProgress else null,
                moduleProgress = if (downloadKind == "module") downloadProgress else null,
                onOpenRelease = { openRelease() },
                onDownloadModule = { downloadModule() },
                onOpenThirdPartyLicenses = { push(Route.ThirdPartyLicenses) },
                onBack = { backStack.removeLastOrNull() },
            )
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
                "文件位置：" + (AppLog.externalCrashFile()?.absolutePath
                    ?: AppLog.lastCrashFile()?.absolutePath ?: "未知"),
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

    // 选完救砖包先警告一次：救砖是最后一道保险 选错等于没有
    val pending = pendingRescue
    if (pending != null) {
        WindowDialog(
            show = true,
            title = "警告",
            summary = "请确保该界面能够正常使用",
            summaryColor = MiuixTheme.colorScheme.error,
            maxWidth = 560.dp,
            // 点空白处不关闭 只能走下面两个按钮
            onDismissRequest = {},
        ) {
            Text(
                pending.name,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
            )
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "取消",
                    onClick = {
                        // 取消这次选择：删掉刚写入的新包 旧配置和旧包都不动
                        runCatching { pending.delete() }
                        pendingRescue = null
                        pendingPrevious = ""
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "继续",
                    onClick = {
                        ApkStore.commitRescue(context, pending, pendingPrevious)
                        LogConfig.rescuePath = pending.absolutePath
                        LogConfig.rescueSize = pending.length()
                        LogConfig.save(context)
                        pendingRescue = null
                        pendingPrevious = ""
                        toast(context, "已选择救砖apk")
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }

    // 主页塞了系统界面本体：和设置里的增强救砖同款宽对话框 直接把人赶去 SystemUI 页
    val blocked = sysUiBlock
    if (blocked != null) {
        val byRescue = blocked == SysUiBlock.Rescue
        WindowDialog(
            show = true,
            title = if (byRescue) "救砖apk不能是系统界面" else "这个包不能装",
            summary = "系统界面本体 请走 SystemUI 页的模块流程",
            maxWidth = 560.dp,
            onDismissRequest = { sysUiBlock = null },
        ) {
            Text(
                if (byRescue) {
                    "$SYSTEMUI_PACKAGE 是系统界面本体 主页的救砖是把崩掉的待测apk" +
                        "装回正常版本 把系统界面装进去既救不回来 装错一次还会黑砖 "
                } else {
                    "$SYSTEMUI_PACKAGE 是系统界面本体 主页会把待测apk直接装进系统 " +
                        "装坏一次就是黑砖 所以这里不让选 "
                },
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.subtitle,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "要" + (if (byRescue) "换" else "测") + "系统界面请去「SystemUI」 那边走模块挂载和救砖守护 " +
                    "崩了会自动换回救砖包",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
            )
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "知道了",
                    onClick = { sysUiBlock = null },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "前往 SystemUI",
                    onClick = {
                        sysUiBlock = null
                        jumpTab = 0
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
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
    onOpenUiSettings: () -> Unit,
    onOpenInterfaceSettings: () -> Unit,
    onOpenConfig: () -> Unit,
    onOpenVolumeCheck: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenAppLog: () -> Unit,
    onOpenCache: () -> Unit,
    onOpenThirdPartyLicenses: () -> Unit,
    onOpenAbout: () -> Unit,
    latest: UpdateInfo?,
    moduleLatest: UpdateInfo?,
    onCheckUpdate: () -> Unit,
    checkingUpdate: Boolean,
    onSetConf: (String, String) -> Unit,
    onRescueToggle: (Boolean) -> Unit,
    onSetCrashLimit: (Int) -> Unit,
) {
    // 三页：界面 / 主页 / 设置 默认落在中间的主页
    val pagerState = rememberPagerState(initialPage = HOME_PAGE, pageCount = { 3 })
    var selected by remember { mutableIntStateOf(HOME_PAGE) }
    // SystemUI 页换成九宫格图标（原图的圆角底板已经去掉 只留 3×3 方块）
    // 深浅两档靠 alpha 区分 底栏的 tint 只换颜色 不动透明度
    val systemUiIcon = ImageVector.vectorResource(R.drawable.ic_systemui_grid)
    val navItems = remember(systemUiIcon) {
        listOf(
            GlassNavItem("SystemUI", systemUiIcon),
            GlassNavItem("主页", MiuixIcons.Home),
            GlassNavItem("设置", MiuixIcons.Settings),
        )
    }
    val scope = rememberCoroutineScope()

    // 渲染阶段埋点：崩了能知道是第几个 tab / 哪一层出的问题
    LaunchedEffect(selected) {
        AppLog.stage("主界面 tab=${listOf("SystemUI", "主页", "设置").getOrElse(selected) { "?" }}")
    }

    // 用 settledPage（动画结束后的落点）而不是 currentPage：
    // 底栏从「界面」点到「设置」时 currentPage 中途会路过 1（主页）
    // 若用它同步 selected 底栏会在动画中途跳到「主页」
    // 反过来又触发一次 animateScrollToPage(1) —— 结果永远停在中间页
    LaunchedEffect(pagerState.settledPage) {
        selected = pagerState.settledPage
    }

    // 主页误选系统界面包被拦下后 点弹窗里的「前往 SystemUI」直接切过去
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
    // 标准底栏走 miuix 的 textureBlur（材质与 miuix-example 一致），需要它自己的一份采样层：
    // 只在标准底栏模式下挂到内容层，液态玻璃模式不录制 不会白花这份开销
    val standardBarBackdrop = rememberBlurBackdrop()
    CompositionLocalProvider(LocalIsWideScreen provides shouldShowSplitPane()) {
        // 底栏是浮在内容上的（内容延伸到它后面 玻璃采样才有东西）
        // 所以不走 Scaffold 的 bottomBar —— 那会把内容顶开 玻璃后面就是空背景
        // 两种底栏高度不同：标准条 64dp 贴底 液态玻璃悬浮条再加 6dp 呼吸
        val bottomBarHeight = if (UiConfigState.iosBottomBar) 70.dp else StandardBottomNavHeight
        val pagePadding = PaddingValues(
            bottom = WindowInsets.navigationBars.asPaddingValues()
                .calculateBottomPadding() + bottomBarHeight,
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
                    .kyantLayerBackdrop(backdrop)
                    .then(
                        if (!UiConfigState.iosBottomBar && standardBarBackdrop != null) {
                            Modifier.layerBackdrop(standardBarBackdrop)
                        } else {
                            Modifier
                        },
                    ),
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
                            moduleLatest = moduleLatest,
                            onOpenAbout = onOpenAbout,
                            onPickRescue = onPickRescue,
                            onPickTarget = onPickTarget,
                            onStart = onStart,
                            onStop = onStop,
                            onOpenSettings = onOpenInterfaceSettings,
                        )

                        // 主页：自己的测试脚本 自带开抓与救砖 不碰模块守护
                        1 -> MainPage(
                            padding = pagePadding,
                            state = state,
                            rootOk = rootOk,
                            latest = latest,
                            onOpenAbout = onOpenAbout,
                            onPickRescue = onPickMainRescue,
                            onPickTarget = onPickMainTarget,
                            onOpenConfig = onOpenConfig,
                        )

                        // 设置：运行 / 自动救砖 / 外观 / 调试 / 关于入口
                        else -> SettingsPage(
                            padding = pagePadding,
                            state = state,
                            onSetConf = onSetConf,
                            onOpenUiSettings = onOpenUiSettings,
                            onOpenDebug = onOpenDebug,
                            onOpenAppLog = onOpenAppLog,
                            onOpenCache = onOpenCache,
                            onOpenAbout = onOpenAbout,
                            onCheckUpdate = onCheckUpdate,
                            checkingUpdate = checkingUpdate,
                        )
                    }
                }
            }

            // 底栏浮层：垫在导航栏之上、宽度照原版收窄到 93%
            // 液态玻璃条是悬浮胶囊 自己不吃 inset（由这层壳抬起来）；
            // 标准底栏要一路铺到屏幕底 所以 inset 交给它自己处理
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .then(if (UiConfigState.iosBottomBar) Modifier.navigationBarsPadding() else Modifier),
                contentAlignment = Alignment.BottomCenter,
            ) {
                if (UiConfigState.iosBottomBar) {
                    GlassBottomNav(
                        selectedIndex = { selected },
                        onTabSelected = { idx ->
                            selected = idx
                            scope.launch { pagerState.animateScrollToPage(idx) }
                        },
                        items = navItems,
                        backdrop = backdrop,
                    )
                } else {
                    StandardBottomNav(
                        selectedIndex = { selected },
                        onTabSelected = { idx ->
                            selected = idx
                            scope.launch { pagerState.animateScrollToPage(idx) }
                        },
                        items = navItems,
                        backdrop = standardBarBackdrop,
                    )
                }
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
