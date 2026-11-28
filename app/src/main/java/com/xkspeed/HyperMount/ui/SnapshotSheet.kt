package com.xkspeed.HyperMount.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xkspeed.HyperMount.core.Snapshot
import com.xkspeed.HyperMount.core.Snapshots
import com.xkspeed.HyperMount.i18n.t
import com.kyant.backdrop.backdrops.layerBackdrop
import com.xkspeed.HyperMount.ui.component.GlassSheetHeader
import com.xkspeed.HyperMount.ui.component.SHEET_HEADER_HEIGHT
import com.xkspeed.HyperMount.ui.component.SHEET_HEADER_LIFT
import com.xkspeed.HyperMount.ui.component.SteppedBottomSheet
import com.xkspeed.HyperMount.ui.component.rememberSheetBackdrop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 底部弹出里的几种视图：列表 → 单条详情 → 重命名 / 删除确认 */
private enum class SnapshotSheetView { List, Detail, Rename, Delete }

/**
 * 快照面板
 *
 * 不单开页面：界面页 / 组件页点「快照」直接从底部升起，记一份、挑一份设成救砖、
 * 重命名或导出，看完往下一划就回去了。走 [SteppedBottomSheet]（30/50/80 三档）而不是
 * Overlay 版 —— Overlay 版要宿主 Scaffold 提供弹层容器，这几页用的是自己的 ScrollPage，接了也不弹。
 *
 * @param kind [Snapshots.KIND_SYSTEMUI] 或 [Snapshots.KIND_PLUGIN]，决定记录哪一份、列哪些
 */
@Composable
fun SnapshotSheet(show: Boolean, kind: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isPlugin = kind == Snapshots.KIND_PLUGIN

    // 标题行那颗玻璃钮与整条模糊的采样源（与主页顶栏同一套）
    val backdrop = rememberSheetBackdrop()

    var view by remember { mutableStateOf(SnapshotSheetView.List) }
    var detail by remember { mutableStateOf<Snapshot?>(null) }
    var renameText by remember { mutableStateOf("") }
    var capturing by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // 每次拉开都回到列表并重读一次：另一个入口刚记的那份这边也要看到
    LaunchedEffect(show) {
        if (show) {
            view = SnapshotSheetView.List
            detail = null
            capturing = false
            working = false
            searching = false
            query = ""
            Snapshots.load(context)
        }
    }

    // 进详情/重命名/删除就退出搜索：那几个视图里筛选没有意义
    LaunchedEffect(view) {
        if (view != SnapshotSheetView.List) {
            searching = false
            query = ""
        }
    }

    val list = Snapshots.of(kind)
    val shown = if (query.isBlank()) {
        list
    } else {
        list.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    val title = when (view) {
        SnapshotSheetView.List ->
            if (isPlugin) t("组件快照", "Component snapshots") else t("界面快照", "Interface snapshots")
        SnapshotSheetView.Detail -> detail?.name.orEmpty()
        SnapshotSheetView.Rename -> t("重命名", "Rename")
        SnapshotSheetView.Delete -> t("删除快照", "Delete snapshot")
    }

    val capture = {
        if (!capturing && !working) {
            capturing = true
            scope.launch {
                val err = Snapshots.capture(context, kind)
                capturing = false
                if (err != null) {
                    UiFeedback.toast(err)
                } else {
                    // 刚记的那条就在列表最前面：撤销直接删它，不用再弹一次确认
                    val fresh = Snapshots.of(kind).firstOrNull()
                    val undo = UiFeedback.show(
                        t("已记录当前版本", "Snapshot saved"),
                        t("撤销", "Undo"),
                    )
                    if (undo && fresh != null) {
                        Snapshots.remove(context, fresh)
                        UiFeedback.toast(t("已删除刚记录的快照", "Snapshot removed"))
                    }
                }
            }
        }
    }

    SteppedBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 18.dp),
        headerHeight = SHEET_HEADER_HEIGHT,
        headerLift = SHEET_HEADER_LIFT,
        hideHandle = true,
        // 搜索态别抢输入框的选词手势
        headerDragEnabled = !searching,
        scrollResetKey = view,
        header = { expanded ->
            GlassSheetHeader(
                title = title,
                backdrop = backdrop,
                expanded = expanded,
                leadingIcon = MiuixIcons.Download,
                leadingDescription = if (capturing) {
                    t("正在复制…", "Copying…")
                } else {
                    t("记录当前快照", "Take a snapshot")
                },
                onLeading = capture,
                leadingTintAlpha = if (capturing) 0.4f else 1f,
                onClose = onDismiss,
                searchable = view == SnapshotSheetView.List,
                searching = searching,
                query = query,
                onQueryChange = { query = it },
                onOpenSearch = { searching = true },
                onCloseSearch = {
                    searching = false
                    query = ""
                },
                searchHint = t("搜索快照名", "Search snapshots"),
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().layerBackdrop(backdrop)) {
            // 列表 → 详情 → 重命名：内容高度差一大截，直接换就是"啪"地缩下去。
            // SizeTransform 把容器高度也纳入动画，面板才是平滑地收 / 展
            AnimatedContent(
                targetState = view,
                transitionSpec = {
                    (fadeIn(tween(170)) togetherWith fadeOut(tween(110)))
                        .using(SizeTransform(clip = false))
                },
                label = "snapshotView",
            ) { current ->
                Column(Modifier.fillMaxWidth()) {
                    when (current) {
                        SnapshotSheetView.List -> {
                            if (list.isEmpty()) {
                                Text(
                                    t("还没有快照", "No snapshots yet"),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 26.dp),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.subtitle,
                                    textAlign = TextAlign.Center,
                                )
                            } else if (shown.isEmpty()) {
                                Text(
                                    t("没有匹配的快照", "No matching snapshots"),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 26.dp),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.subtitle,
                                    textAlign = TextAlign.Center,
                                )
                            } else {
                                shown.forEach { snap ->
                                    SnapshotRow(snap) {
                                        detail = snap
                                        view = SnapshotSheetView.Detail
                                    }
                                }
                            }
                        }

                        SnapshotSheetView.Detail -> {
                            val snap = detail
                            if (snap != null) {
                                Text(
                                    "${snap.kindLabel} · ${snap.version} · ${snap.sizeLabel} · ${snap.timeLabel}",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 2.dp, bottom = 6.dp),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.subtitle,
                                    textAlign = TextAlign.Center,
                                )
                                SheetGroup {
                                    SheetAction(t("重命名", "Rename"), divider = false) {
                                        renameText = snap.name
                                        view = SnapshotSheetView.Rename
                                    }
                                    if (snap.exists) {
                                        SheetAction(t("设为救砖", "Use as rescue")) {
                                            if (working) return@SheetAction
                                            working = true
                                            scope.launch {
                                                val err = Snapshots.setAsRescue(context, snap)
                                                working = false
                                                UiFeedback.toast(
                                                    err ?: if (snap.kind == Snapshots.KIND_PLUGIN) {
                                                        t("已设为救砖组件", "Set as rescue component")
                                                    } else {
                                                        t("已复制到模块 作为救砖界面", "Copied into the module as the rescue build")
                                                    },
                                                )
                                            }
                                        }
                                        SheetAction(t("导出", "Export")) {
                                            scope.launch {
                                                val err = Snapshots.share(context, snap)
                                                if (err != null) UiFeedback.toast(err)
                                            }
                                        }
                                    }
                                    SheetAction(
                                        text = t("删除", "Delete"),
                                        color = MiuixTheme.colorScheme.error,
                                    ) {
                                        view = SnapshotSheetView.Delete
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                SheetGroup {
                                    SheetAction(
                                        text = t("返回", "Back"),
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        divider = false,
                                    ) {
                                        view = SnapshotSheetView.List
                                        detail = null
                                    }
                                }
                            }
                        }

                        SnapshotSheetView.Rename -> {
                            val snap = detail
                            if (snap != null) {
                                TextField(
                                    value = renameText,
                                    onValueChange = { renameText = it },
                                    label = t("名称", "Name"),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(18.dp))
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    TextButton(
                                        text = t("取消", "Cancel"),
                                        onClick = { view = SnapshotSheetView.Detail },
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(20.dp))
                                    TextButton(
                                        text = t("保存", "Save"),
                                        onClick = {
                                            val name = renameText
                                            view = SnapshotSheetView.Detail
                                            scope.launch {
                                                val err = Snapshots.rename(context, snap, name)
                                                if (err != null) {
                                                    UiFeedback.toast(err)
                                                } else {
                                                    detail = Snapshots.of(kind).firstOrNull { it.id == snap.id } ?: snap
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.textButtonColorsPrimary(),
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                SheetGroup {
                                    SheetAction(
                                        text = t("返回", "Back"),
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        divider = false,
                                    ) { view = SnapshotSheetView.Detail }
                                }
                            }
                        }

                        SnapshotSheetView.Delete -> {
                            val snap = detail
                            if (snap != null) {
                                Text(
                                    t(
                                        "下载目录里的文件会一起删掉，之后不能再用来恢复",
                                        "The file in Downloads is deleted too and can't be used to restore later",
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.subtitle,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(18.dp))
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    TextButton(
                                        text = t("取消", "Cancel"),
                                        onClick = { view = SnapshotSheetView.Detail },
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(20.dp))
                                    TextButton(
                                        text = t("删除", "Delete"),
                                        onClick = {
                                            view = SnapshotSheetView.List
                                            detail = null
                                            scope.launch { Snapshots.remove(context, snap) }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.textButtonColorsPrimary(),
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                SheetGroup {
                                    SheetAction(
                                        text = t("返回", "Back"),
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        divider = false,
                                    ) { view = SnapshotSheetView.Detail }
                                }
                            }
                        }
                        }
                }
            }
        }
    }
}

/** 列表里的一条：名字 + 版本 / 大小 / 时间，右侧一个箭头表示还能点进去 */
@Composable
private fun SnapshotRow(snap: Snapshot, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                snap.name,
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.main,
            )
            Text(
                buildString {
                    append(snap.version)
                    append(" · ").append(snap.sizeLabel)
                    append(" · ").append(snap.timeLabel)
                    if (!snap.exists) append(t(" · 文件已丢失", " · file missing"))
                },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
            )
        }
        Text(
            "›",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.main,
        )
    }
}

/**
 * 面板里的一组操作：一张圆角卡，行都长在里面
 *
 * 面板底色是 surface，卡片用 surfaceContainer 提一层，行与行之间只靠卡内那条
 * 内缩的细线分开 —— 线不贯通整块面板，看起来是「卡里的分隔」而不是「面板被切开」。
 */
@Composable
internal fun SheetGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer),
        content = content,
    )
}

/**
 * 卡片里的一行操作：整行可点、居中
 *
 * @param divider 顶上画一条 1px 细线，左右各缩 16dp。组里第一行不画。
 * 线画在这一行自己的绘制层里、贴着上边，不额外占高度。
 */
@Composable
internal fun SheetAction(
    text: String,
    color: Color = MiuixTheme.colorScheme.primary,
    divider: Boolean = true,
    onClick: () -> Unit,
) {
    val lineColor = MiuixTheme.colorScheme.dividerLine
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (divider) {
                    // 线以 y 为中心画，压在行边界上会被裁掉一半，往里挪半个线宽
                    val stroke = 1.dp.toPx()
                    val half = stroke / 2f
                    val inset = 16.dp.toPx()
                    drawLine(
                        color = lineColor,
                        start = Offset(inset, half),
                        end = Offset(size.width - inset, half),
                        strokeWidth = stroke,
                    )
                }
            }
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, style = MiuixTheme.textStyles.main)
    }
}
