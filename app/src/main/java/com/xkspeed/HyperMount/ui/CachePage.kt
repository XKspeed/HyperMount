package com.xkspeed.HyperMount.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xkspeed.HyperMount.core.CacheConfig
import com.xkspeed.HyperMount.core.Logcat
import com.xkspeed.HyperMount.core.Module
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.ui.components.SectionCard
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MB = 1024L * 1024L

/**
 * 缓存上限的五个档位（MB）
 *
 * 0 表示不限制 滑条上这五个点等距排开 下标是 0..4 值再换算成实际容量
 * 1 GB 按 1024 MB 存 显示时再换算 免得滑条上出现 1024 MB 这种别扭的数
 */
private val LIMIT_OPTIONS_MB = listOf(0L, 100L, 200L, 500L, 1024L)

/** 老配置里可能存着 300 MB 这种中间值 进来时落到最近的一档 */
private fun nearestOptionIndex(limitMb: Long): Int =
    LIMIT_OPTIONS_MB.indices.minByOrNull { abs(LIMIT_OPTIONS_MB[it] - limitMb) } ?: 0

/** 档位文字：单位全大写 1024 MB 写成 1 GB */
private fun optionLabel(mb: Long): String = when {
    mb <= 0L -> "无限制"
    mb >= 1024L -> "${mb / 1024L} GB"
    else -> "$mb MB"
}

/**
 * 缓存管理
 *
 * 只管路 cache/stage 里那份待测 apk 副本：调上限、勾选删除。
 * 上限本身就是修剪阈值，改小会立刻按新值收一次。
 */
@Composable
fun CachePage(onBack: () -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var total by remember { mutableStateOf(0L) }
    var marked by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 滑条值是档位下标 0..4：无限制 / 100 MB / 200 MB / 500 MB / 1 GB 点与点等距
    // 老配置不在档位上时先对齐到最近的一档 用户动过一次才落盘
    var limitIndex by remember {
        mutableFloatStateOf(nearestOptionIndex(CacheConfig.limit / MB).toFloat())
    }
    var askDelete by remember { mutableStateOf(false) }
    // 空状态要把「无缓存」放在占用卡与底部删除条之间：记卡底与浮层顶的位置，
    // 两者相减才是真正能用的那块空白 光靠 weight 会被列表的顶部内边距带偏
    var cardBottomPx by remember { mutableIntStateOf(0) }
    var overlayTopPx by remember { mutableIntStateOf(0) }

    fun reload() {
        val list = Logcat.stageFiles()
        files = list
        total = list.sumOf { it.length() }
        // 文件没了就把勾选一起收掉 免得删一个不存在的东西
        marked = marked.filter { p -> list.any { it.absolutePath == p } }.toSet()
    }

    /** 把当前档位写进去 顺便按新上限收一次 */
    fun persistLimit() {
        val idx = limitIndex.roundToInt().coerceIn(0, LIMIT_OPTIONS_MB.lastIndex)
        CacheConfig.set(context, LIMIT_OPTIONS_MB[idx] * MB)
        Logcat.pruneStage()
        reload()
    }

    LaunchedEffect(Unit) { reload() }

    val limitMb = LIMIT_OPTIONS_MB[limitIndex.roundToInt().coerceIn(0, LIMIT_OPTIONS_MB.lastIndex)]
    val limitBytes = limitMb * MB
    // 已用可能比上限还大 进度条画满为止；无限制没有分母 留空
    val ratio = if (limitBytes <= 0L) 0f else (total.toFloat() / limitBytes).coerceIn(0f, 1f)
    val picked = files.filter { it.absolutePath in marked }
    val pickedSize = picked.sumOf { it.length() }
    // 卡底到浮层顶的距离；首帧还没量到时按 0 算，「无缓存」等量到再画
    val emptyGap = with(LocalDensity.current) {
        (cardBottomPx - overlayTopPx).coerceAtLeast(0).toDp()
    }

    ScrollPage(
        title = "缓存",
        onBack = onBack,
        // 底部固定条不吃列表高度：末尾多留一条 滚到底时最后一项停在它上方
        bottomPadding = WindowInsets.navigationBars.asPaddingValues()
            .calculateBottomPadding() + 112.dp,
        // 删除条和底栏一样钉在页面底部 不跟着列表滚：
        // 列表滚动时它会从卡片底下穿过去 所以先垫一层从透明到页面底色的渐变
        overlay = {
            // 整页高的 Column：上面让出占用卡，中间那块空白给「无缓存」居中，
            // 删除条自然落在最底下 —— 不用另外估删除条有多高
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned {
                        overlayTopPx = it.positionInWindow().y.toInt()
                    },
            ) {
                Spacer(Modifier.height(emptyGap))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (files.isEmpty() && cardBottomPx > 0) {
                        Text(
                            "无缓存",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.subtitle,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, MiuixTheme.colorScheme.surface)
                            )
                        )
                        .padding(top = 20.dp)
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = picked.isNotEmpty()) { askDelete = true }
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (picked.isEmpty()) "删除" else "删除 ${picked.size} 项",
                                color = if (picked.isEmpty()) {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                } else {
                                    MiuixTheme.colorScheme.error
                                },
                                style = MiuixTheme.textStyles.main,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        },
    ) {
        item { SmallTitle("占用") }
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    // 记下卡底位置：空状态的「无缓存」要落在卡与删除条之间
                    .onGloballyPositioned {
                        cardBottomPx = (it.positionInWindow().y + it.size.height).toInt()
                    }
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    // 全长 = 上限，填满的部分 = 当前占用
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MiuixTheme.colorScheme.secondaryVariant)
                    ) {
                        if (ratio > 0f) {
                            Box(
                                Modifier
                                    .fillMaxWidth(ratio)
                                    .fillMaxHeight()
                                    .background(MiuixTheme.colorScheme.primary)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            "已用 " + Module.fmtSize(total),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.subtitle,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "上限 " + optionLabel(limitMb),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.subtitle,
                        )
                    }
                    // 只留轨道：标题和右侧数值都不显示 大小看下面那排档位就够了
                    Slider(
                        value = limitIndex,
                        onValueChange = { limitIndex = it },
                        onValueChangeFinished = { persistLimit() },
                        valueRange = 0f..LIMIT_OPTIONS_MB.lastIndex.toFloat(),
                        // 五个点之间夹三个刻度 间距完全相等 拖动只会停在整档上
                        steps = LIMIT_OPTIONS_MB.size - 2,
                        showKeyPoints = true,
                        hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                    // 档位文字直接可点：点哪档就跳哪档 不用非得拖过去
                    LimitTicks(
                        selected = limitIndex.roundToInt(),
                        onSelect = { i ->
                            limitIndex = i.toFloat()
                            persistLimit()
                        },
                    )
                }
            }
        }

        if (files.isNotEmpty()) {
            item {
                SectionCard {
                    files.forEach { f ->
                        val checked = f.absolutePath in marked
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    marked = if (checked) {
                                        marked - f.absolutePath
                                    } else {
                                        marked + f.absolutePath
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    f.name,
                                    style = MiuixTheme.textStyles.main,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    Module.fmtSize(f.length()),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.subtitle,
                                )
                            }
                            Checkbox(
                                state = if (checked) ToggleableState.On else ToggleableState.Off,
                                onClick = {
                                    marked = if (checked) {
                                        marked - f.absolutePath
                                    } else {
                                        marked + f.absolutePath
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    WindowDialog(
        show = askDelete,
        title = "删除缓存",
        summary = "释放 ${Module.fmtSize(pickedSize)}",
        maxWidth = 560.dp,
        onDismissRequest = { askDelete = false },
    ) {
        Text(
            "将删除 ${picked.size} 个 apk 缓存 下次测试会重新复制一份",
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.subtitle,
        )
        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(
                text = "取消",
                onClick = { askDelete = false },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = "删除",
                onClick = {
                    Logcat.deleteStage(picked)
                    marked = emptySet()
                    askDelete = false
                    reload()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * 滑条下面的五个档位文字
 *
 * 位置按滑轨来算：滑轨两端各让出半个 thumb 才能放圆钮 所以文字中心也照这个内缩
 * 摆出来正好对着上面的关键点 点哪段就跳到哪档 不用非得把圆钮拖过去
 */
@Composable
private fun LimitTicks(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val inset = with(density) { SliderDefaults.MinHeight.toPx() / 2f }
    val active = MiuixTheme.colorScheme.primary
    val idle = MiuixTheme.colorScheme.onSurfaceVariantSummary

    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        content = {
            LIMIT_OPTIONS_MB.forEachIndexed { i, mb ->
                Text(
                    text = optionLabel(mb),
                    color = if (i == selected) active else idle,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable { onSelect(i) }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints()) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            val last = placeables.lastIndex
            placeables.forEachIndexed { i, p ->
                val center = if (last <= 0) width / 2f else inset + (width - 2 * inset) * i / last
                val x = (center - p.width / 2f).roundToInt().coerceIn(0, (width - p.width).coerceAtLeast(0))
                p.place(x, 0)
            }
        }
    }
}
