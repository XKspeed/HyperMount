package com.xkspeed.HyperMount.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xkspeed.HyperMount.core.CacheConfig
import com.xkspeed.HyperMount.core.Logcat
import com.xkspeed.HyperMount.core.Module
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.ui.components.sliderTapFraction
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import com.xkspeed.HyperMount.ui.util.stableNavigationBarHeight
import com.xkspeed.HyperMount.ui.util.stableNavigationBarsPadding

private const val MB = 1024L * 1024L

// ---- 上限滑条的三段：100 MB 起 到 1 GB 是无级可拖，最右留一小截给「无限制」 ----

private const val MIN_MB = 100f
private const val ONE_GB_MB = 1024f
// 1 GB 和「无限制」的标签得有地方摆 最后这一段留够宽度 不然两个胶囊会压在一起
private const val UNLIMITED_MB = 1250f

/** 滑条上的四个节点：拖动到附近会被吸过去 */
private val NODES = listOf(MIN_MB, 500f, ONE_GB_MB, UNLIMITED_MB)

/** 吸附范围：拉到 48 MB，靠近就把胶囊和文字一起吸过去 */
private const val SNAP_MB = 48f

/** iOS 底栏那种弹性：起步猛 落位带一点点回弹 */
private val IosTabSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 380f)

/** 超过 1 GB 那一档就算无限制：中间不会停在 1050 MB 这种没意义的数 */
private fun isUnlimited(mb: Float) = mb > ONE_GB_MB + 1f

/** 当前上限怎么显示 */
private fun limitLabel(mb: Float): String = when {
    isUnlimited(mb) -> "无限制"
    mb >= ONE_GB_MB - 1f -> "1 GB"
    else -> "${mb.roundToInt()} MB"
}

/** 靠近节点就吸附，点击轨道时也用同一套 */
private fun snapToNode(mb: Float): Float {
    val near = NODES.minByOrNull { abs(it - mb) } ?: return mb
    return if (abs(near - mb) <= SNAP_MB) near else mb
}

/**
 * 缓存管理
 *
 * 只管路 cache/stage 里那份待测 apk 副本：调上限、勾选删除。
 * 上限本身就是修剪阈值，改小会立刻按新值收一次。
 *
 * 上半部分是「占用 + 上限」两段，不套卡片，只用一条横线隔开；
 * 缓存文件才用卡片装。
 */
@Composable
fun CachePage(onBack: () -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var total by remember { mutableStateOf(0L) }
    var marked by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 滑条值就是 MB 数：100..1024 连续可拖，最右一小截是无限制
    // 老配置里的中间值原样接上，不再对齐到档位
    var limitMb by remember {
        val saved = CacheConfig.limit / MB
        mutableFloatStateOf(
            if (saved <= 0L) UNLIMITED_MB else saved.toFloat().coerceIn(MIN_MB, ONE_GB_MB)
        )
    }
    var dragging by remember { mutableStateOf(false) }
    var sliderWidthPx by remember { mutableIntStateOf(0) }

    var askDelete by remember { mutableStateOf(false) }
    // 空状态要把「无缓存」放在上限区与底部删除条之间
    var sectionBottomPx by remember { mutableIntStateOf(0) }
    var overlayTopPx by remember { mutableIntStateOf(0) }

    fun reload() {
        val list = Logcat.stageFiles()
        files = list
        total = list.sumOf { it.length() }
        // 文件没了就把勾选一起收掉 免得删一个不存在的东西
        marked = marked.filter { p -> list.any { it.absolutePath == p } }.toSet()
    }

    /** 把当前值写进去 顺便按新上限收一次 */
    fun persistLimit() {
        val bytes = if (isUnlimited(limitMb)) 0L else limitMb.roundToInt().toLong() * MB
        CacheConfig.set(context, bytes)
        Logcat.pruneStage()
        reload()
    }

    LaunchedEffect(Unit) { reload() }

    val limitBytes = if (isUnlimited(limitMb)) 0L else limitMb.roundToInt().toLong() * MB
    // 已用可能比上限还大 进度条画满为止；无限制没有分母 留空
    val ratio = if (limitBytes <= 0L) 0f else (total.toFloat() / limitBytes).coerceIn(0f, 1f)
    val picked = files.filter { it.absolutePath in marked }
    val pickedSize = picked.sumOf { it.length() }
    // 区底到浮层顶的距离；首帧还没量到时按 0 算
    val emptyGap = with(LocalDensity.current) {
        (sectionBottomPx - overlayTopPx).coerceAtLeast(0).toDp()
    }

    // 胶囊：按住就出现，一路跟着手指走，吸到节点时再弹过去
    val bubbleAlpha by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = tween(120),
        label = "limitBubble",
    )
    // 拖动中必须和 Miuix Slider 内部用同一组弹簧（0.9 / 1755），
    // 换成别的曲线滑块是实时的、胶囊是慢的，一眼就能看错位；
    // 松手落位才换成 iOS 底栏那条弹性
    val followMb by animateFloatAsState(
        targetValue = limitMb,
        animationSpec = if (dragging) {
            spring(dampingRatio = 0.9f, stiffness = 1755f)
        } else {
            IosTabSpring
        },
        label = "limitFollow",
    )
    val fraction = ((followMb - MIN_MB) / (UNLIMITED_MB - MIN_MB)).coerceIn(0f, 1f)

    ScrollPage(
        title = "缓存",
        onBack = onBack,
        // 底部固定条不吃列表高度：末尾多留一条 滚到底时最后一项停在它上方
        bottomPadding = stableNavigationBarHeight() + 112.dp,
        // 删除条和底栏一样钉在页面底部 不跟着列表滚
        overlay = {
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
                    if (files.isEmpty() && sectionBottomPx > 0) {
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
                        .stableNavigationBarsPadding()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Card(modifier = Modifier.fillMaxWidth()) {
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
        // ---- 占用：进度条 + 已用/上限 不套卡片 ----
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 18.dp),
            ) {
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
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "已用 " + Module.fmtSize(total),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.subtitle,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "上限 " + limitLabel(limitMb),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.subtitle,
                    )
                }
            }
        }

        // 一条横线把两个分区隔开
        item { DividerLine() }

        // ---- 上限：无级滑条，拖动时上方浮一个胶囊 ----
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 10.dp, bottom = 6.dp)
                    .onSizeChanged { sliderWidthPx = it.width }
                    // 记下这一段的底：空状态的「无缓存」要落在它与删除条之间
                    .onGloballyPositioned {
                        sectionBottomPx = (it.positionInWindow().y + it.size.height).toInt()
                    },
            ) {
                // 节点文字 + 拖动胶囊：吸上去的瞬间节点文字消失，胶囊带着它的文字弹过去
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(30.dp),
                ) {
                    val density = LocalDensity.current
                    // 轨道两端各让出半个圆钮，和 Slider 内部算法一致
                    val radius = with(density) { 10.dp.toPx() }
                    val usable = (sliderWidthPx - 2f * radius).coerceAtLeast(1f)

                    // 落进吸附范围的那个节点：胶囊要停到它头顶，文字也换成它的
                    val taken = if (dragging) {
                        NODES.minByOrNull { abs(it - limitMb) }?.takeIf { abs(it - limitMb) <= SNAP_MB }
                    } else {
                        null
                    }
                    val bubbleX = radius + fraction * usable

                    if (sliderWidthPx > 0) {
                        NODES.forEach { node ->
                            val f = ((node - MIN_MB) / (UNLIMITED_MB - MIN_MB)).coerceIn(0f, 1f)
                            val cx = radius + f * usable
                            // 被接管就退场：字已经由胶囊替它报了，留着会重影
                            val vanish by animateFloatAsState(
                                targetValue = if (taken != null && abs(taken - node) < 0.5f) 1f else 0f,
                                animationSpec = tween(120),
                                label = "nodeVanish$node",
                            )
                            if (vanish < 0.99f) {
                                Box(
                                    Modifier
                                        .align(Alignment.CenterStart)
                                        // 宽度跟着文字走，用绘制阶段补半个宽度做居中
                                        .graphicsLayer {
                                            translationX = cx - size.width / 2f
                                            alpha = 1f - vanish
                                            val s = 1f - 0.22f * vanish
                                            scaleX = s
                                            scaleY = s
                                        },
                                ) {
                                    Text(
                                        limitLabel(node),
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        style = MiuixTheme.textStyles.subtitle.copy(fontSize = 10.sp),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }

                    if (bubbleAlpha > 0.01f) {
                        // 实色：背后压着的节点文字不能透出来
                        val bubbleBg = lerp(
                            MiuixTheme.colorScheme.surface,
                            MiuixTheme.colorScheme.primary,
                            0.16f,
                        )
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .graphicsLayer { translationX = bubbleX - size.width / 2f }
                                .alpha(bubbleAlpha)
                                .clip(RoundedCornerShape(50))
                                .background(bubbleBg)
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        ) {
                            Text(
                                limitLabel(limitMb),
                                color = MiuixTheme.colorScheme.primary,
                                style = MiuixTheme.textStyles.subtitle.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
                }

                Slider(
                    value = limitMb,
                    onValueChange = {
                        dragging = true
                        limitMb = snapToNode(it)
                    },
                    onValueChangeFinished = {
                        dragging = false
                        persistLimit()
                    },
                    valueRange = MIN_MB..UNLIMITED_MB,
                    // 不设档位：100 MB 到 1 GB 之间随便拖
                    steps = 0,
                    showKeyPoints = true,
                    keyPoints = NODES,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sliderTapFraction { f ->
                            val v = snapToNode(MIN_MB + f * (UNLIMITED_MB - MIN_MB))
                            limitMb = v
                            dragging = false
                            persistLimit()
                        },
                )
            }
        }

        item { DividerLine() }

        // ---- 缓存文件：这里才用卡片 ----
        if (files.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Column {
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

/** 分区之间的那一条横线 */
@Composable
private fun DividerLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(1.dp)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    )
}
