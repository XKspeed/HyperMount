package com.xkspeed.HyperMount.ui

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.xkspeed.HyperMount.core.AppLog
import com.xkspeed.HyperMount.core.Module
import com.xkspeed.HyperMount.core.ModuleState
import com.xkspeed.HyperMount.core.UpdateChannel
import com.xkspeed.HyperMount.i18n.AppLang
import com.xkspeed.HyperMount.i18n.t
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.ui.component.glass.TopBarTuning
import com.xkspeed.HyperMount.ui.components.SectionCard
import com.xkspeed.HyperMount.ui.components.locateHighlight
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import com.xkspeed.HyperMount.ui.components.LiquidSelectHost
import com.xkspeed.HyperMount.ui.components.LiquidSelectPreference
import com.xkspeed.HyperMount.ui.components.rememberLiquidSelectState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsPage(
    padding: PaddingValues,
    state: ModuleState,
    onSetConf: (String, String) -> Unit,
    onOpenUiSettings: () -> Unit = {},
    onOpenDebug: () -> Unit = {},
    onOpenCache: () -> Unit = {},
    onOpenMcp: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    checkingUpdate: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

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
            toast(context, t("这个位置拿不到真实路径 手动填一下", "Can't resolve a real path here — type it manually"))
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
                toast(context, t("打不开文件选择器", "Can't open the file picker"))
            }
    }

    val select = rememberLiquidSelectState()
    val scope = rememberCoroutineScope()

    // 拦截提示把用户带到这里时：滚到「日志目录」再点亮
    // large-title(0) + 「运行」标题 + 日志目录卡
    val listState = rememberLazyListState()
    LaunchedEffect(Locate.pending) {
        if (Locate.pending == LocateTarget.LOG_DIR) {
            runCatching { listState.animateScrollToItem(2) }
            Locate.consume()
            Locate.hint(LocateTarget.LOG_DIR)
        }
    }

    ScrollPage(
        title = t("设置", "Settings"),
        listState = listState,
        // 主题入口挪到右上角：与返回键同款玻璃圆钮 图标用 Miuix 的 Theme
        onTopAction = onOpenUiSettings,
        topActionIcon = rememberVectorPainter(MiuixIcons.Theme),
        topActionDescription = t("界面与主题", "Interface & theme"),
        // 尺寸与位移走调试页那几项 默认 24dp 居中
        topActionIconSize = TopBarTuning.actionIconSize.dp,
        topActionIconOffset = DpOffset(
            TopBarTuning.actionOffsetX.dp,
            TopBarTuning.actionOffsetY.dp,
        ),
        // 与主页/关于一致：底栏高度（70dp）已由外层 pagePadding 给出 这里只加一点呼吸
        bottomPadding = padding.calculateBottomPadding() + 16.dp,
        overlay = { bd -> LiquidSelectHost(select, bd, bottomInset = padding.calculateBottomPadding()) },
    ) {
                // 语言切换放整个设置页最上方：中英两份文案全在代码里 跟着这份偏好走
                item {
                    SectionCard {
                        LiquidSelectPreference(
                            title = t("语言", "Language"),
                            items = listOf(
                                t("跟随系统", "Follow system"),
                                "简体中文",
                                "English",
                            ),
                            selectedIndex = AppLang.pref,
                            onSelectedIndexChange = { idx ->
                                AppLang.setPref(idx, context)
                                // 模块端（安装提示 / 操作台 / module.prop 描述）也按这个值判断中英文：
                                // 0 跟随系统 1 简体中文 2 English。没 root 写不进去，
                                // 模块会退回系统语言，不影响 App 自己
                                scope.launch {
                                    runCatching { Module.setConf("UI_LANG", idx.toString()) }
                                }
                            },
                            state = select,
                        )
                    }
                }
                item { SmallTitle(t("运行", "General")) }
                item {
                    SectionCard {
                        // 运行模式挪到「界面 → 界面设置」了 这里只留路径与缓存
                        // 日志目录不再跟着运行模式收起：主页测试也往这里写日志
                        // 模式 0（完全禁用）还得能选 否则主页永远开不了测试
                        ArrowPreference(
                            title = t("日志目录", "Log directory"),
                            summary = state.logDir.ifBlank { t("未设置 · 点击选择目录", "Not set · tap to choose") },
                            // 点按跳文件选择器；长按走手填，给选择器解析不出路径时留一条主动入口
                            onClick = null,
                            modifier = Modifier
                                .locateHighlight(LocateTarget.LOG_DIR)
                                .combinedClickable(
                                    onClickLabel = t("选择目录", "Choose folder"),
                                    onLongClickLabel = t("手动输入目录", "Type path manually"),
                                    onClick = { pickLogFolder() },
                                    onLongClick = {
                                        manualDirText = state.logDir
                                        showManualDir = true
                                    },
                                ),
                        )
                        // 缓存跟日志模式无关：关掉日志也能进来清副本
                        ArrowPreference(
                            title = t("缓存大小", "Cache size"),
                            onClick = onOpenCache,
                        )
                    }
                }

                // 应用日志与 OOBE 都挪进「开发者选项」二级页，设置页不再单开调试组

                // 接口：给 AI 客户端的 MCP 入口 默认关闭
                item { SmallTitle(t("接口", "API")) }
                item {
                    SectionCard {
                        ArrowPreference(
                            title = t("MCP 服务", "MCP service"),
                            onClick = onOpenMcp,
                        )
                    }
                }

                // 关于整页挪成二级页 四行并成一张卡：关于 / 检查更新 / 自动检查更新 / 更新来源
                item { SmallTitle(t("关于", "About")) }
                item {
                    SectionCard {
                        ArrowPreference(
                            title = t("关于", "About"),
                            onClick = onOpenAbout,
                        )
                        // 检查更新：普通基础组件 没有副标题 右边只留箭头
                        ArrowPreference(
                            title = if (checkingUpdate) t("检查中…", "Checking…") else t("检查更新", "Check for updates"),
                            enabled = !checkingUpdate,
                            onClick = onCheckUpdate,
                        )
                        SwitchPreference(
                            checked = UiConfigState.autoCheckUpdate,
                            onCheckedChange = {
                                UiConfigState.autoCheckUpdate = it
                                UiConfigState.save(context)
                            },
                            title = t("自动检查更新", "Auto check"),
                        )
                        // 更新来源正好管着上面那两行查到的是哪条线 挨着放
                        // 换来源会重新查一次（见 App.kt 的 LaunchedEffect）
                        LiquidSelectPreference(
                            title = t("更新来源", "Update channel"),
                            items = UpdateChannel.entries.map { t(it.label, it.enLabel) },
                            selectedIndex = UiConfigState.updateChannel.ordinal,
                            onSelectedIndexChange = {
                                val channel = UpdateChannel.entries[it]
                                if (channel != UiConfigState.updateChannel) {
                                    UiConfigState.updateChannel = channel
                                    UiConfigState.save(context)
                                }
                            },
                            state = select,
                        )
                    }
                }

                // 开发者选项：单独一张卡、放在全页最下方，正式包也保留这个入口
                item {
                    SectionCard {
                        ArrowPreference(
                            title = t("开发者选项", "Developer options"),
                            onClick = onOpenDebug,
                        )
                    }
                }
            }

    // MT 回不来目录路径时的兜底：手动填（默认带上当前配置值）
    WindowDialog(
        show = showManualDir,
        title = t("日志目录", "Log directory"),
        summary = t("目录不存在会自动创建 改完对下一次抓取生效", "Missing folders are created automatically; applies from the next capture"),
        maxWidth = 560.dp,
        onDismissRequest = { showManualDir = false },
    ) {
        TextField(
            value = manualDirText,
            onValueChange = { manualDirText = it },
            // 空时居中、输入后上浮缩小：和示例里的 With title 同款
            label = t("目录路径", "Directory path"),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = t("取消", "Cancel"),
                onClick = { showManualDir = false },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = t("保存", "Save"),
                onClick = {
                    val p = manualDirText.trim()
                    showManualDir = false
                    if (p.isEmpty()) {
                        toast(context, t("路径不能为空", "Path can't be empty"))
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
