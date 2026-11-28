package com.xkspeed.HyperMount.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 救砖组件选择面板
 *
 * 和快照面板共用同一套底部弹出模板（玻璃标题行、无极高度、贴顶下拉关闭），
 * 区别只有左上角：那边是「记录快照」，这里是「关闭」—— 这一屏只挑一份，不再记新的。
 * 从组件快照里挑一份当崩溃后的回滚目标；挑不出来就直接回滚系统预装版。
 *
 * @param onPick 选中某一份快照（文件还在的那种）
 * @param onClear 不用快照，回到系统预装版
 */
@Composable
fun RescuePickSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    onPick: (Snapshot) -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    val backdrop = rememberSheetBackdrop()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // 每次拉开都重读一次：刚在快照面板里记下的那份这边也要能挑到
    LaunchedEffect(show) {
        if (show) {
            searching = false
            query = ""
            Snapshots.load(context)
        }
    }

    val list = Snapshots.of(Snapshots.KIND_PLUGIN)
    val shown = if (query.isBlank()) {
        list
    } else {
        list.filter { it.name.contains(query.trim(), ignoreCase = true) }
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
        header = { expanded ->
            GlassSheetHeader(
                title = t("救砖组件", "Rescue component"),
                backdrop = backdrop,
                expanded = expanded,
                leadingIcon = MiuixIcons.Close,
                leadingDescription = t("关闭", "Close"),
                onLeading = onDismiss,
                searchable = true,
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
            when {
                list.isEmpty() -> EmptyHint(
                    t(
                        "还没有组件快照，先去「组件快照」记一份",
                        "No component snapshots yet — record one first",
                    ),
                )

                shown.isEmpty() -> EmptyHint(t("没有匹配的快照", "No matching snapshots"))

                else -> {
                    SheetGroup {
                        shown.forEachIndexed { index, snap ->
                            val usable = snap.exists
                            SheetAction(
                                text = snap.name + if (usable) "" else t("（文件已丢失）", " (missing)"),
                                color = if (usable) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                                divider = index > 0,
                            ) {
                                if (usable) onPick(snap)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            SheetGroup {
                SheetAction(
                    text = t("不用快照 · 回滚系统预装版", "No snapshot — roll back to the system build"),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    divider = false,
                ) { onClear() }
            }
        }
    }
}

/** 列表空态：一行居中的提示，直接在面板底色上，不套卡片 */
@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 26.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.subtitle,
        textAlign = TextAlign.Center,
    )
}
