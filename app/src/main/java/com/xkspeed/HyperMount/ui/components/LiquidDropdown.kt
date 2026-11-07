package com.xkspeed.HyperMount.ui.components

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur as contentBlur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.xkspeed.HyperMount.ui.component.glass.InteractiveHighlight
import com.xkspeed.HyperMount.ui.component.glass.glassBlurScale
import com.xkspeed.HyperMount.ui.util.isInDarkTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/*
 * 液态玻璃下拉（逐值移植自 Sonify `ui/widgets/basic/LiquidDropdown.kt`
 * + 设置页 `ui/pages/settings/Item.kt` 的 SelectItem 用法，动画参数不做改动）：
 *  - 展开由单条 fraction 驱动：缩放 0.24→1、transformOrigin 从锚点角插值到中心、
 *    方向性裁剪揭示（从 42dp 方块双向长成面板）、内容 8dp 模糊渐隐
 *  - 进场 spring(0.78/240) + alpha tween(120)，退出 spring(0.78/400) + alpha tween(400)
 *  - 行尾「当前值 + 箭头」按 0.4 alpha 起步，展开时 tween(180) 淡出（滞回 0.15/0.2）
 *  - 面板用 kyant backdrop 采样页面做真模糊，与顶栏玻璃同一套材质
 */

// =====================================================================
// 动画参数（逐值对齐 Sonify ListPopupLayout 当前版本 不是旧的 ListPopupDefaults）
//
// 进场 spring 0.77/220 退场 0.85/650：退场更硬 收得干脆；
// 锚点迁移 0.77/420 比尺寸更快到 1 先「移向中心」再放大；
// 退场锚点不单开曲线：与尺寸共用同一条弹簧 保证两者同时归零（终点才对得上）。
// =====================================================================

private val FractionEnterAnimSpec =
    spring<Float>(dampingRatio = 0.77f, stiffness = 220f, visibilityThreshold = 0.0001f)
private val FractionExitAnimSpec =
    spring<Float>(dampingRatio = 0.85f, stiffness = 650f, visibilityThreshold = 0.0001f)

private val OriginEnterAnimSpec =
    spring<Float>(dampingRatio = 0.77f, stiffness = 420f, visibilityThreshold = 0.0001f)

// 材质与内容的消隐完全由 fraction 推 不再另开一条 alpha 轨：
// 玻璃 f*7 很快就满 退场 f*3.6 才全透明；内容 (f-0.22)/0.4 淡入 退场 (f-0.30)/0.48
private const val ContainerAlphaEnter = 7f
private const val ContainerAlphaExit = 3.6f
private const val ContentEnterFrom = 0.22f
private const val ContentEnterTo = 0.62f
private const val ContentExitFrom = 0.30f
private const val ContentExitTo = 0.78f

// 收起态副本的淡出系数：进场 3.2 留得久一点 长大过程中不会提前消失；退场 1.7
private const val CollapseIconKEnter = 3.2f
private const val CollapseIconKExit = 1.7f

/** 材质透明度：进场 f*7（f≈0.14 就满） 退场 f*3.6 */
private fun containerAlpha(f: Float, entering: Boolean): Float =
    (f * if (entering) ContainerAlphaEnter else ContainerAlphaExit).coerceIn(0f, 1f)

/** 内容透明度：进场在 f=0.62 就完全显出来 退场撑到 0.30 才开始淡 */
private fun contentAlpha(f: Float, entering: Boolean): Float {
    val from = if (entering) ContentEnterFrom else ContentExitFrom
    val to = if (entering) ContentEnterTo else ContentExitTo
    return ((f - from) / (to - from)).coerceIn(0f, 1f)
}

/** 驼峰模糊：两端清晰、中间最糊（不是单调递减） */
private fun contentBlur(f: Float): Dp = 6.dp * (1f - abs(2f * f - 1f)).coerceIn(0f, 1f)

private val PopupMinWidth = 200.dp
private val PopupMaxWidth = 288.dp
private val PopupMinHeight = 50.dp
private val PopupCornerRadius = 25.dp
private val PopupShadowPadding = 24.dp

/** 收起态尺寸的额外补偿：宽 +14 高 +10 中心正好比触发区偏左 7dp 偏上 5dp 由定位偏移抵消 */
private val CollapseExtraWidth = 14.dp
private val CollapseExtraHeight = 10.dp

private val DropdownButtonDiameter = 42.dp

/** 弹层动画进度：fraction 是揭示进度（弹簧会过冲），position 是实际朝向 */
data class LiquidDropdownProgress(
    val fraction: Float,
    val position: LiquidDropdownLayoutPosition,
    val pivotX: Float = 1f,
)

data class LiquidDropdownLayoutPosition(
    val showBelow: Boolean = true,
    val showAbove: Boolean = false,
    val isRightAligned: Boolean = true,
)

/** 页面底部被底栏遮住的高度：下拉定位与高度上限都会避开这块区域 */
val LocalBottomOverlayHeight = compositionLocalOf { 0.dp }

private data class LiquidDropdownPositionResult(
    val offset: IntOffset,
    val layoutPosition: LiquidDropdownLayoutPosition,
)

/**
 * 窗口系定位（Sonify `calculateDropdownPosition` 原样）：
 * 锚点是行尾「当前值 + 箭头」那块内容 —— 面板右缘比它右 7dp、上缘比它高 5dp，
 * 配合收起态尺寸 +14/+10 的补偿 收起态矩形的中心正好落在触发文字上。
 * 下方放不下翻到上方，都不够时垂直居中，最终 clamp 进窗口。
 */
private fun Density.calculateDropdownPosition(
    anchorBounds: IntRect,
    windowBounds: IntRect,
    outerSize: IntSize,
    visibleSize: IntSize,
): LiquidDropdownPositionResult {
    val offsetDeltaX = 7.dp.roundToPx()
    val offsetDeltaY = 5.dp.roundToPx()

    val offsetX = anchorBounds.right - outerSize.width + offsetDeltaX
    val spaceBelow = windowBounds.bottom - anchorBounds.bottom
    val spaceAbove = anchorBounds.top - windowBounds.top
    val offsetY: Int
    val position: LiquidDropdownLayoutPosition
    if (spaceBelow > visibleSize.height) {
        offsetY = anchorBounds.top - offsetDeltaY
        position = LiquidDropdownLayoutPosition(showBelow = true)
    } else if (spaceAbove > visibleSize.height) {
        offsetY = anchorBounds.bottom - outerSize.height + offsetDeltaY
        position = LiquidDropdownLayoutPosition(showBelow = false, showAbove = true)
    } else {
        offsetY = anchorBounds.top + anchorBounds.height / 2 - outerSize.height / 2
        position = LiquidDropdownLayoutPosition(showBelow = false, showAbove = false)
    }

    val clamped = IntOffset(
        x = offsetX.coerceIn(
            windowBounds.left,
            (windowBounds.right - outerSize.width).coerceAtLeast(windowBounds.left),
        ),
        y = offsetY.coerceIn(
            windowBounds.top,
            (windowBounds.bottom - outerSize.height).coerceAtLeast(windowBounds.top),
        ),
    )
    return LiquidDropdownPositionResult(clamped, position)
}

/**
 * 弹层主体：调用方把它放在页面根 Box 的顶层（同窗口同帧），
 * 这样动画首帧不会被独立 Popup 窗口的创建延迟截断，backdrop 也能采到页面内容。
 */
@Composable
fun LiquidDropdownLayout(
    expanded: Boolean,
    anchorBounds: IntRect,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit = {},
    backdrop: Backdrop? = null,
    /** 触发区「当前值 + 箭头」的窗口坐标：弹窗从这块内容原位长成面板 */
    collapseBounds: IntRect = IntRect.Zero,
    /** 收起态显示的内容（触发区副本） 随容器长大淡出 */
    collapseContent: (@Composable () -> Unit)? = null,
    onFractionProgress: ((LiquidDropdownProgress) -> Unit)? = null,
    /** 跟手滑选状态：非 null 时面板手势独占指针、行改由面板统一命中（见 DropdownPanelDragSelect） */
    dragSelectState: DropdownPanelDragSelectState? = null,
    content: @Composable () -> Unit,
) {
    val fractionProgress = remember { Animatable(0f) }
    val originProgress = remember { Animatable(0f) }
    var internalVisible by remember { mutableStateOf(false) }
    var layoutPosition by remember { mutableStateOf(LiquidDropdownLayoutPosition()) }
    var anchorOrigin by remember { mutableStateOf(TransformOrigin(1f, 0f)) }
    // 画布左上角在窗口里的位置：首帧内容还没测量，收起态矩形要靠它换算到画布坐标
    var canvasWindowPos by remember { mutableStateOf(IntOffset.Zero) }
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val currentOnDismissFinished by rememberUpdatedState(onDismissFinished)
    val currentOnFractionProgress by rememberUpdatedState(onFractionProgress)
    val bottomOverlayPx = with(LocalDensity.current) { LocalBottomOverlayHeight.current.toPx() }

    // 两轨并行：fraction 管尺寸/淡入淡出、origin 管锚点从角落移到中心（更快到 1）
    // 透明度不再单开一条轨 由 fraction 直接推（见 containerAlpha / contentAlpha）
    LaunchedEffect(expanded) {
        if (expanded) {
            internalVisible = true
            launch { fractionProgress.animateTo(1f, FractionEnterAnimSpec) }
            launch { originProgress.animateTo(1f, OriginEnterAnimSpec) }
        } else {
            if (!internalVisible) return@LaunchedEffect
            launch { fractionProgress.animateTo(0f, FractionExitAnimSpec) }
            // 退场让锚点与尺寸用同一条弹簧：
            // 原来锚点走 tween(340) 尺寸走 spring(650) 两条曲线收尾不同步，
            // 最后几帧矩形中心已经离开触发文字 但尺寸还没归零，文字就会「对不齐再跳回来」。
            // 同 spec 同起点同时启动 → 每一帧 p == f，f 归零时锚点必然也在原位。
            originProgress.animateTo(0f, FractionExitAnimSpec)
            fractionProgress.snapTo(0f)
            originProgress.snapTo(0f)
            internalVisible = false
            currentOnDismissFinished()
        }
    }

    LaunchedEffect(Unit) {
        currentOnFractionProgress?.let { callback ->
            snapshotFlow {
                LiquidDropdownProgress(
                    fractionProgress.value,
                    layoutPosition,
                    anchorOrigin.pivotFractionX,
                )
            }.collect { callback(it) }
        }
    }

    if (!expanded && !internalVisible) return

    var scrimOrigin by remember { mutableStateOf(IntOffset.Zero) }

    BackHandler(enabled = expanded) { currentOnDismissRequest() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                scrimOrigin = IntOffset(
                    it.positionInWindow().x.toInt(),
                    it.positionInWindow().y.toInt(),
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { currentOnDismissRequest() })
            },
    ) {
        Box(
            modifier = Modifier
                .layout { measurable, constraints ->
                    val padPx = PopupShadowPadding.roundToPx()
                    val minHeightPx = PopupMinHeight.roundToPx()
                    val safeBottomPx = (constraints.maxHeight - bottomOverlayPx)
                        .coerceAtLeast(0f)
                        .toInt()
                    val maxHeightPx = (safeBottomPx - 2 * padPx).coerceAtLeast(minHeightPx)
                    val placeable = measurable.measure(
                        constraints.copy(
                            minWidth = PopupMinWidth.roundToPx()
                                .coerceAtMost(constraints.maxWidth),
                            minHeight = 0,
                            maxHeight = maxHeightPx,
                        ),
                    )
                    val outerSize = IntSize(placeable.width, placeable.height)
                    val windowBounds = IntRect(0, 0, constraints.maxWidth, safeBottomPx)
                    // 定位要用「可见面板」尺寸 不能拿含 48dp 阴影留白的画布去算：
                    // 那样算出来的 offset 会让面板整体偏左偏上各一个 padding
                    val panelSize = IntSize(
                        (outerSize.width - 2 * padPx).coerceAtLeast(1),
                        (outerSize.height - 2 * padPx).coerceAtLeast(1),
                    )
                    val result = calculateDropdownPosition(
                        anchorBounds, windowBounds, panelSize, panelSize,
                    )
                    if (result.layoutPosition != layoutPosition) {
                        layoutPosition = result.layoutPosition
                    }
                    // 缩放锚点：菜单右上角（向下展开时贴着触发行的那个角）
                    val origin = TransformOrigin(
                        pivotFractionX = if (result.layoutPosition.isRightAligned) 1f else 0f,
                        pivotFractionY = if (result.layoutPosition.showAbove) 1f else 0f,
                    )
                    if (origin != anchorOrigin) {
                        anchorOrigin = origin
                    }
                    // result.offset 是面板左上角 画布左上角还要再往左上让出一个 padding
                    val canvasOffset = IntOffset(result.offset.x - padPx, result.offset.y - padPx)
                    if (canvasOffset != canvasWindowPos) {
                        canvasWindowPos = canvasOffset
                    }
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(
                            IntOffset(
                                x = canvasOffset.x - scrimOrigin.x,
                                y = canvasOffset.y - scrimOrigin.y,
                            ),
                        )
                    }
                },
        ) {
            LiquidDropdownContent(
                fractionProgress = { fractionProgress.value },
                originProgress = { originProgress.value },
                isEntering = expanded,
                layoutPosition = layoutPosition,
                backdrop = backdrop,
                collapseBounds = collapseBounds,
                collapseContent = collapseContent,
                canvasWindowPos = canvasWindowPos,
                dragSelectState = dragSelectState,
                content = content,
            )
        }
    }
}

@Composable
private fun LiquidDropdownContent(
    fractionProgress: () -> Float,
    originProgress: () -> Float,
    isEntering: Boolean,
    layoutPosition: LiquidDropdownLayoutPosition,
    backdrop: Backdrop?,
    collapseBounds: IntRect,
    collapseContent: (@Composable () -> Unit)?,
    /** 画布左上角的窗口坐标：首帧换算收起态矩形用 */
    canvasWindowPos: IntOffset,
    /** 跟手滑选状态：非 null 时面板手势独占指针、行改由面板统一命中 */
    dragSelectState: DropdownPanelDragSelectState? = null,
    content: @Composable () -> Unit,
) {
    val isDark = isInDarkTheme()
    val containerColor = if (isDark) Color(0xFF242424) else Color.White
    val glassSurfaceAlpha = if (isDark) 0.8f else 0.72f
    val fallbackSurfaceAlpha = 0.97f
    val edgeLightColor =
        if (!isDark) Color.White.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.4f)
    val density = LocalDensity.current
    val padPx = with(density) { PopupShadowPadding.toPx() }
    val extraW = with(density) { CollapseExtraWidth.toPx() }
    val extraH = with(density) { CollapseExtraHeight.toPx() }

    // 内容自然尺寸（px）：内层测量后回填。首帧为 0 时面板还完全透明 看不到错位
    var natural by remember { mutableStateOf(IntSize.Zero) }
    val natW = natural.width.toFloat()
    val natH = natural.height.toFloat()

    // 收起态矩形 = 触发区「当前值 + 箭头」的实测尺寸 + (14dp, 10dp)
    // 定位偏移给的是 7dp / 5dp 两边正好抵消 所以 f=0 时矩形中心就落在触发文字上
    val hasCollapse = collapseBounds.width > 0 && collapseBounds.height > 0
    val collapseW = if (hasCollapse) collapseBounds.width + extraW
    else with(density) { DropdownButtonDiameter.toPx() }
    val collapseH = if (hasCollapse) collapseBounds.height + extraH
    else with(density) { DropdownButtonDiameter.toPx() }

    // 面板矩形（画布坐标）：两条边各自插值 不是整体 scale 所以圆角不会被拉成椭圆
    // 锚在锚点角（右上/右下） 宽度长出来的同时整体按 k = 0.5p(1-s) 往面板中心迁
    // 迁移进度 originProgress 比 fraction 更早到 1：先「移过去」再「放大」
    fun rectOf(f: Float, p: Float): FloatArray {
        // 首帧：内容还没测量 natW = 0 用内容区做基准会把矩形推到左边界（看起来往左闪一下）
        // 这一帧直接拿触发区的实测矩形：f = 0 时两者本来就重合（面板右缘 = 触发右缘 + 7dp）
        if (natW <= 0f) {
            if (!hasCollapse) {
                return floatArrayOf(padPx, padPx, collapseW, collapseH)
            }
            val cx = (collapseBounds.left + collapseBounds.right) / 2f - canvasWindowPos.x
            val cy = (collapseBounds.top + collapseBounds.bottom) / 2f - canvasWindowPos.y
            return floatArrayOf(cx - collapseW / 2f, cy - collapseH / 2f, collapseW, collapseH)
        }
        // 弹簧会过冲：f 略微小于 0 或大于 1 时矩形会越过收起态 / 展开态
        // 关闭到最后一帧就会「抖一下再回来」 这里夹住，只留两端之间的行程
        val ff = f.coerceIn(0f, 1f)
        val w = collapseW + (natW - collapseW) * ff
        val h = collapseH + (natH - collapseH) * ff
        val s = if (natW > 0f) w / natW else 1f
        val k = 0.5f * p.coerceIn(0f, 1f) * (1f - s)
        val right = layoutPosition.isRightAligned
        val bottom = layoutPosition.showAbove
        val migrationX = (if (right) -1f else 1f) * natW * k
        val migrationY = (if (bottom) -1f else 1f) * natH * k
        val leftRaw = (if (right) natW - w else 0f) + migrationX
        val topRaw = (if (bottom) natH - h else 0f) + migrationY
        return floatArrayOf(padPx + leftRaw, padPx + topRaw, w, h)
    }

    // 每帧换一个 shape 实例：drawBackdrop / clip / 边缘光都按引用缓存 shape
    // 复用同一个实例会让描边和身影冻结在第一帧的小矩形上
    val frame = (fractionProgress() * 400f).toInt()
    val panelShape = remember(frame, natW, natH) {
        PanelRectShape(PopupCornerRadius) { rectOf(fractionProgress(), originProgress()) }
    }
    // 内容层在 24dp 内缩里 坐标系整体比画布小一圈
    val contentShape = remember(frame, natW, natH) {
        PanelRectShape(PopupCornerRadius) {
            val r = rectOf(fractionProgress(), originProgress())
            floatArrayOf(r[0] - padPx, r[1] - padPx, r[2], r[3])
        }
    }

    // 按压光晕：按住不放时从手指位置绽开一团弥散白光 跟手移动
    // 半径随展开进度收小（收起态面板小 固定 150dp 的光会漫出圆角）
    // 用 pressOnly 版本：只观察按下 不消费事件 行内点击照常
    val glowScope = rememberCoroutineScope()
    val glow = remember(glowScope) {
        InteractiveHighlight(
            animationScope = glowScope,
            radiusScale = { 1f - 0.6f * fractionProgress().coerceIn(0f, 1f) },
            radiusBaseDp = 150.dp,
        )
    }

    // 展开进度登记给状态：面板外的驱动方（长按触发区）据此判断何时能开始命中。
    // 必须成对登记与清理，否则状态对象会指着已死亡的 Animatable
    dragSelectState?.registerFraction { fractionProgress() }
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            // 跟手滑选：面板上唯一的手势状态，按下即命中、逐帧改命中、松手执行。
            // 它会消费事件，所以光晕不再自挂手势，改由下面三个回调直接驱动
            .then(
                if (dragSelectState != null) {
                    Modifier.dropdownPanelDragSelect(
                        state = dragSelectState,
                        fraction = { fractionProgress() },
                        hapticFeedback = haptic,
                        onPointerDown = glow::externalDragStart,
                        onPointerMove = glow::externalDragMove,
                        onPointerUp = glow::externalDragEnd,
                    )
                } else {
                    Modifier
                },
            )
            // 面板在 root 里的原点，供菜单项把 boundsInRoot 换算成手势用的局部坐标
            // （轴向区间用 y，二维命中的横截区间用 x，两个原点都写）。
            // boundsInRoot 不含 graphicsLayer 变换，所以形变不影响这个换算
            .onGloballyPositioned {
                dragSelectState?.let { state ->
                    val bounds = it.boundsInRoot()
                    state.panelTopInRoot = bounds.top
                    state.panelLeftInRoot = bounds.left
                }
            }
            // 按压 / 跟手拉伸 / tanh 阻尼位移整块面板一起做（玻璃、边光、阴影、内容）
            .dropdownPanelDragTransform(
                fraction = { fractionProgress() },
                pressProgress = { glow.pressProgress },
                dragOffset = { glow.offset },
            )
            .drawBehind {
            val alpha = containerAlpha(fractionProgress(), isEntering)
            if (alpha <= 0f) return@drawBehind
            val r = rectOf(fractionProgress(), originProgress())
            val w = r[2].coerceAtLeast(0f)
            val h = r[3].coerceAtLeast(0f)
            if (w <= 0f || h <= 0f) return@drawBehind
            val baseAlpha = (32 * alpha).toInt().coerceIn(0, 255)
            val blurRadius = 16f * this.density
            val radius = PopupCornerRadius.toPx().coerceAtMost(min(w, h) / 2f)
            val nativePath = android.graphics.Path().apply {
                addRoundRect(
                    r[0], r[1], r[0] + w, r[1] + h,
                    radius, radius,
                    android.graphics.Path.Direction.CW,
                )
            }
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(baseAlpha, 0, 0, 0)
                maskFilter = BlurMaskFilter(
                    blurRadius.coerceAtLeast(0.1f),
                    BlurMaskFilter.Blur.NORMAL,
                )
            }
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawPath(nativePath, paint)
            }
        },
    ) {
        // 玻璃层铺满整个画布（含 24dp 阴影留白） 面板矩形在画布内由 shape 决定
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    alpha = containerAlpha(fractionProgress(), isEntering)
                },
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .then(
                        if (backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Modifier.drawBackdrop(
                                backdrop = backdrop,
                                shape = { panelShape },
                                effects = {
                                    vibrancy()
                                    blur(24.dp.toPx() * glassBlurScale)
                                },
                                highlight = { null },
                                shadow = { null },
                                onDrawSurface = {
                                    drawRect(containerColor.copy(alpha = glassSurfaceAlpha))
                                },
                            )
                        } else {
                            Modifier.drawBehind {
                                val r = rectOf(fractionProgress(), originProgress())
                                val w = r[2].coerceAtLeast(0f)
                                val h = r[3].coerceAtLeast(0f)
                                if (w <= 0f || h <= 0f) return@drawBehind
                                drawRoundRect(
                                    color = containerColor.copy(alpha = fallbackSurfaceAlpha),
                                    topLeft = androidx.compose.ui.geometry.Offset(r[0], r[1]),
                                    size = Size(w, h),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                        PopupCornerRadius.toPx().coerceAtMost(min(w, h) / 2f),
                                    ),
                                )
                            }
                        },
                    ),
            )

            // 内容层：等比缩放（按宽度）并移到矩形中心 圆心/圆角都不跟着变形
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .rectEdgeLight(
                        shapeProvider = { panelShape },
                        color = edgeLightColor,
                        strokeWidth = 0.28f.dp,
                        blurRadius = 0.8f.dp,
                    ),
            )
        }

        // 按压光晕：夹在材质之上、内容之下（放内容上面会把行文字刷白）
        // 开了跟手滑选时手势归面板独占，这里只负责画，按压/位移由 externalDrag* 驱动
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(panelShape)
                .then(if (dragSelectState == null) glow.pressOnlyModifier else Modifier)
                .then(glow.modifier),
        )

        Box(
            modifier = Modifier
                .padding(PopupShadowPadding)
                .onSizeChanged { natural = it },
        ) {
            Box(
                modifier = Modifier
                    .clip(contentShape)
                    .graphicsLayer {
                        val f = fractionProgress()
                        val p = originProgress()
                        val r = rectOf(f, p)
                        val s = if (natW > 0f) r[2] / natW else 1f
                        scaleX = s
                        scaleY = s
                        alpha = contentAlpha(f, isEntering)
                        // 内容自然中心在画布 (pad + natW/2, pad + natH/2)
                        translationX = (r[0] + r[2] / 2f) - (padPx + natW / 2f)
                        translationY = (r[1] + r[3] / 2f) - (padPx + natH / 2f)
                    }
                    .contentBlur(
                        // 驼峰：两端清晰、中间最糊 —— 不是单调递减
                        radius = contentBlur(fractionProgress()),
                    ),
            ) {
                // 下发给菜单项（登记位置 + 画命中高光）。必须在内容槽位内下发：
                // 菜单项是 content 的后代，在槽位外下发它们读不到
                if (dragSelectState != null) {
                    CompositionLocalProvider(
                        LocalDropdownPanelDragSelect provides dragSelectState,
                    ) {
                        content()
                    }
                } else {
                    content()
                }
            }
        }

        // 收起态内容（当前值 + 箭头）：锁在矩形正中心 随容器长大淡出
        // 这里**不乘**材质透明度：f=0 时它就是全显的 和触发区那份像素重合
        // 触发区在展开当帧就硬切隐藏（见 rememberRowContentAlpha）所以不会重影
        //
        // 第一帧也要画：natW 还是 0，rectOf 会用触发区实测矩形兜底，位置与原位完全重合。
        // 若是「等测量完再画」 第一帧就会两头都空 —— 文字消失一帧再出现 看起来就是闪一下。
        // canvasWindowPos 是画布左上角的窗口坐标 由 layout 阶段写入 绘制时已经是最新值；
        // 它还是 0 说明还没摆过位置 这一帧确实算不出画布坐标 才跳过。
        if (collapseContent != null && (natW > 0f || canvasWindowPos != IntOffset.Zero)) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .graphicsLayer {
                        val k = if (isEntering) CollapseIconKEnter else CollapseIconKExit
                        alpha = (1f - fractionProgress() * k).coerceIn(0f, 1f)
                        val r = rectOf(fractionProgress(), originProgress())
                        translationX = r[0] + r[2] / 2f - size.width / 2f
                        translationY = r[1] + r[3] / 2f - size.height / 2f
                    },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    collapseContent()
                }
            }
        }
    }
}

/**
 * 面板矩形形状：每次 createOutline 现算一遍 宽高与位置都由 fraction 决定
 * 圆角恒定 25dp 不随矩形缩放变化（这正是「不是整体 scale」的意义）
 */
private class PanelRectShape(
    private val cornerRadius: Dp,
    private val rectProvider: () -> FloatArray,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: Density,
    ): Outline {
        val r = rectProvider()
        val w = r[2].coerceAtLeast(0f)
        val h = r[3].coerceAtLeast(0f)
        val radius = with(density) { cornerRadius.toPx().coerceAtMost(min(w, h) / 2f) }
        return Outline.Rounded(
            androidx.compose.ui.geometry.RoundRect(
                left = r[0],
                top = r[1],
                right = r[0] + w,
                bottom = r[1] + h,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
            ),
        )
    }
}

/** 圆角矩形边缘光：描边画在形状边界上再裁进形状内，NORMAL 模糊 + ADD 混合 */
private fun Modifier.rectEdgeLight(
    shapeProvider: DrawScope.() -> Shape,
    color: Color,
    strokeWidth: Dp,
    blurRadius: Dp,
): Modifier = drawWithCache {
    val path = Path()
    onDrawWithContent {
        drawContent()
        if (color.alpha <= 0f || strokeWidth <= 0.dp) return@onDrawWithContent
        val outline = shapeProvider().createOutline(
            size = size,
            layoutDirection = layoutDirection,
            density = this@drawWithCache,
        )
        path.rewind()
        when (outline) {
            is Outline.Rounded -> path.addRoundRect(outline.roundRect)
            is Outline.Generic -> path.addPath(outline.path)
            is Outline.Rectangle -> path.addRect(outline.rect)
        }
        val strokePx = ceil(strokeWidth.toPx().coerceAtMost(size.minDimension / 2f)) * 2f
        val blurPx = blurRadius.toPx()
        val nativePath = path.asAndroidPath()
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                this.color = color.toArgb()
                style = android.graphics.Paint.Style.STROKE
                this.strokeWidth = strokePx
                if (blurPx > 0f) {
                    maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
                }
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
            }
            canvas.save()
            canvas.nativeCanvas.clipPath(nativePath)
            canvas.nativeCanvas.drawPath(nativePath, paint)
            canvas.restore()
        }
    }
}

// =====================================================================
// 内容列：宽度取前 8 项最大固有宽度，clamp 200~288dp，超高可滚
// =====================================================================

private const val MaxItemsForWidth = 8

/**
 * 内容列：宽度取前 8 项最大固有宽度、clamp 在 200~288dp，超出走内滚。
 *
 * @param onFitsOnScreen 内容是否一屏装得下（不需要滚动）。跟手滑选据此决定是否启用：
 *   装不下时纵向手势归滚动，否则两种手势互相抢。
 */
@Composable
fun LiquidDropdownColumn(
    modifier: Modifier = Modifier,
    onFitsOnScreen: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val scrollState = rememberScrollState()
    val measurePolicy = remember {
        object : MeasurePolicy {
            override fun MeasureScope.measure(
                measurables: List<Measurable>,
                constraints: Constraints,
            ): androidx.compose.ui.layout.MeasureResult {
                val minPx = PopupMinWidth.roundToPx()
                val maxPx = PopupMaxWidth.roundToPx()
                val widthCount = min(MaxItemsForWidth, measurables.size)
                var maxIntrinsic = 0
                for (i in 0 until widthCount) {
                    val w = measurables[i].maxIntrinsicWidth(constraints.maxHeight)
                    if (w > maxIntrinsic) maxIntrinsic = w
                }
                val upper = maxOf(maxPx, constraints.minWidth).coerceAtMost(constraints.maxWidth)
                val lower = maxOf(minPx, constraints.minWidth).coerceAtMost(upper)
                val listWidth = maxIntrinsic.coerceIn(lower, upper)
                val childConstraints = constraints.copy(
                    minWidth = listWidth,
                    maxWidth = listWidth,
                    minHeight = 0,
                )

                val placeables = ArrayList<Placeable>(measurables.size)
                var listHeight = 0
                for (i in measurables.indices) {
                    val p = measurables[i].measure(childConstraints)
                    placeables.add(p)
                    listHeight += p.height
                }
                return layout(listWidth, listHeight) {
                    var currentY = 0
                    for (p in placeables) {
                        p.placeRelative(0, currentY)
                        currentY += p.height
                    }
                }
            }

            override fun IntrinsicMeasureScope.minIntrinsicHeight(
                measurables: List<IntrinsicMeasurable>,
                width: Int,
            ): Int {
                val minPx = PopupMinWidth.roundToPx()
                val maxPx = PopupMaxWidth.roundToPx()
                val widthCount = min(MaxItemsForWidth, measurables.size)
                var maxIntrinsic = 0
                for (i in 0 until widthCount) {
                    val w = measurables[i].maxIntrinsicWidth(Int.MAX_VALUE)
                    if (w > maxIntrinsic) maxIntrinsic = w
                }
                val listWidth = maxIntrinsic.coerceIn(minPx, maxPx)
                return measurables.sumOf { it.minIntrinsicHeight(listWidth) }
            }
        }
    }

    Layout(
        content = content,
        modifier = modifier
            .height(IntrinsicSize.Min)
            .verticalScroll(state = scrollState),
        measurePolicy = measurePolicy,
    )

    // 上报是否需要滚动。用 snapshotFlow 而非 onSizeChanged：maxValue 还会在
    // 「内容没变、只是被 maxHeight 压缩」时变化，那次 onSizeChanged 不触发
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.maxValue <= 1 }
            .collect { onFitsOnScreen(it) }
    }
}

// =====================================================================
// 菜单行：17dp 圆角按下光晕、选中 primary 着色并在尾部显示勾
// =====================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiquidDropdownRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 10.5.dp,
    marquee: Boolean = false,
) {
    // 文字跟主题走（onSurface）：莫奈取色下深浅两态都不是纯黑纯白，
    // 写死 Color.Black/Color.White 会跟卡片里的行差一截
    val contentColor = colorScheme.onSurface
    val selectedColor = colorScheme.primary
    val interactionSource = remember { MutableInteractionSource() }
    // 跟手滑选：面板下发状态时，行不再挂 clickable（两个手势状态互相抢事件正是
    // 「先按住再滑动会跳状态」的根因），改由面板手势层统一命中 + 执行 + 画高亮
    val dragSelect = LocalDropdownPanelDragSelect.current
    val rowShape = RoundedCornerShape(17.dp)
    Row(
        modifier = modifier
            .padding(
                start = 8.dp,
                end = 8.dp,
                top = if (isFirst) 8.dp else 0.dp,
                bottom = if (isLast) 8.dp else 0.dp,
            )
            .clip(rowShape)
            .then(
                if (dragSelect != null) {
                    Modifier.dropdownPanelEntry(
                        enabled = enabled,
                        highlightShape = rowShape,
                        action = onClick,
                    )
                } else {
                    // 点击光晕用主题给的 Indication（Miuix 那套 spring 按压高亮），
                    // 跟卡片里的 ArrowPreference / SwitchPreference 完全同一手感
                    Modifier.clickable(
                        enabled = enabled,
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = onClick,
                    )
                },
            )
            .padding(horizontal = 14.dp, vertical = verticalPadding)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        top.yukonga.miuix.kmp.basic.Text(
            text = text,
            fontSize = 15.6.sp,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            modifier = Modifier
                .weight(1f)
                .then(if (marquee) Modifier.basicMarquee() else Modifier),
            maxLines = if (marquee) 1 else Int.MAX_VALUE,
            softWrap = !marquee,
        )
        Image(
            imageVector = MiuixIcons.Basic.Check,
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (selected) selectedColor else Color.Transparent),
            modifier = Modifier.size(20.dp),
        )
    }
}

// =====================================================================
// 触发元件：设置页的「选择项」
// =====================================================================

/**
 * 行尾内容的显隐（Sonify `DropdownTriggerState` 同款：**硬切** 没有半透明中间态）。
 *
 * 展开**当帧**就藏：弹层从第一帧起就把收起态副本画在原位（几何靠触发区实测矩形算 不依赖内容测量），
 * 所以这一帧就是「原地换人」—— 不空帧、也不会有两份一起存在好几帧（叠起来颜色发深 看起来闪一下）。
 * 若按 fraction 判断会晚一帧：state.fraction 由回调同步 比弹层内部那条 Animatable 慢，
 * 那一帧副本已经长出一截并开始淡出 文字会看起来「跳一下」。
 *
 * 退场要等 fraction 归零（弹层自己 snap 完才降到底）再露出来，
 * 否则面板还在屏幕上时两处文字会重叠。
 */
@Composable
private fun rememberRowContentAlpha(expanded: Boolean, fraction: () -> Float): Float {
    val visible by remember(expanded) {
        derivedStateOf { !expanded && fraction() <= 0.0001f }
    }
    return if (visible) 1f else 0f
}

/** 下拉选择项的共享状态：页面级持有，行与弹层各拿一半 */
class LiquidSelectState {
    internal var expanded by mutableStateOf(false)
    internal var anchorBounds by mutableStateOf(IntRect.Zero)
    /** 行尾「当前值 + 箭头」的窗口坐标：弹窗从这块内容长出来 */
    internal var contentBounds by mutableStateOf(IntRect.Zero)
    internal var items by mutableStateOf<List<String>>(emptyList())
    internal var selected by mutableStateOf(0)
    internal var onSelect: (Int) -> Unit = {}
    internal var fraction by mutableStateOf(0f)
    internal var owner by mutableStateOf<Any?>(null)

    fun close() {
        expanded = false
    }
}

@Composable
fun rememberLiquidSelectState(): LiquidSelectState = remember { LiquidSelectState() }

/**
 * 设置页的选择行：外观是 Miuix 的 BasicComponent，
 * 点开由页面级 [LiquidSelectHost] 画玻璃弹层。
 */
@Composable
fun LiquidSelectPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    state: LiquidSelectState,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val rowKey = remember { Any() }
    var rect by remember { mutableStateOf(IntRect.Zero) }
    // 行尾「当前值 + 箭头」自己的窗口矩形：展开时弹窗从这块文字长出来
    var contentRect by remember { mutableStateOf(IntRect.Zero) }
    val isOpen = state.expanded && state.owner === rowKey
    val rowAlpha = rememberRowContentAlpha(
        expanded = isOpen,
        fraction = { if (state.owner === rowKey) state.fraction else 0f },
    )
    // 行尾「当前值 + 箭头」用 Miuix 的语义色：SliderPreference 的数值就是这个口径
    // （onSurfaceVariantActions + body2）。Sonify 那边是 onBackground 再压 0.4 透明度，
    // 换算过来就是这一档灰度；所以这里不再额外乘 0.4，浮动只交给展开动画
    val actionColor = if (enabled) colorScheme.onSurfaceVariantActions
    else colorScheme.disabledOnSecondaryVariant
    val valueText = items.getOrNull(selectedIndex).orEmpty()

    BasicComponent(
        modifier = modifier.onGloballyPositioned { coords ->
            val pos = coords.positionInWindow()
            val size = coords.size
            rect = IntRect(
                left = pos.x.toInt(),
                top = pos.y.toInt(),
                right = pos.x.toInt() + size.width,
                bottom = pos.y.toInt() + size.height,
            )
        },
        title = title,
        summary = summary,
        endActions = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .onGloballyPositioned { coords ->
                        val pos = coords.positionInWindow()
                        val size = coords.size
                        contentRect = IntRect(
                            left = pos.x.toInt(),
                            top = pos.y.toInt(),
                            right = pos.x.toInt() + size.width,
                            bottom = pos.y.toInt() + size.height,
                        )
                    }
                    .graphicsLayer { alpha = rowAlpha },
            ) {
                if (valueText.isNotBlank()) {
                    top.yukonga.miuix.kmp.basic.Text(
                        text = valueText,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = actionColor,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Image(
                    imageVector = MiuixIcons.Basic.ArrowUpDown,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(actionColor),
                    modifier = Modifier
                        .size(width = 10.dp, height = 16.dp)
                        .align(Alignment.CenterVertically),
                )
            }
        },
        onClick = {
            if (isOpen) {
                state.close()
            } else {
                state.owner = rowKey
                state.anchorBounds = rect
                state.contentBounds = contentRect
                state.items = items
                state.selected = selectedIndex
                state.onSelect = onSelectedIndexChange
                state.expanded = true
                haptics.performHapticFeedback(
                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick,
                )
            }
        },
        role = androidx.compose.ui.semantics.Role.DropdownList,
        enabled = enabled && items.isNotEmpty(),
    )
}

/** 页面级弹层宿主：放在 ScrollPage 的 overlay 槽里 */
@Composable
fun LiquidSelectHost(
    state: LiquidSelectState,
    backdrop: Backdrop?,
    /** 页面底部被底栏等遮住的高度：弹层定位与高度上限会避开这块 */
    bottomInset: Dp = 0.dp,
) {
    // 跟手滑选状态：面板手势与行共用同一份（行经 CompositionLocal 拿到）
    val dragSelect = remember { DropdownPanelDragSelectState() }
    if (!state.expanded && state.fraction <= 0f) return
    androidx.compose.runtime.CompositionLocalProvider(
        LocalBottomOverlayHeight provides bottomInset,
    ) {
    LiquidDropdownLayout(
        expanded = state.expanded,
        // 锚点用行尾「当前值 + 箭头」那块内容 不是整行：
        // 定位偏移 7dp/5dp 与收起态尺寸 +14/+10 是配套的 换成整行会对不上
        anchorBounds = state.contentBounds.takeIf { it.width > 0 } ?: state.anchorBounds,
        onDismissRequest = { state.close() },
        backdrop = backdrop,
        collapseBounds = state.contentBounds,
        collapseContent = {
            // 与行尾那份「当前值 + 箭头」同字号同颜色：展开瞬间两处像素重合
            val text = state.items.getOrNull(state.selected).orEmpty()
            if (text.isNotBlank()) {
                top.yukonga.miuix.kmp.basic.Text(
                    text = text,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = colorScheme.onSurfaceVariantActions,
                )
                Spacer(Modifier.width(8.dp))
            }
            Image(
                imageVector = MiuixIcons.Basic.ArrowUpDown,
                contentDescription = null,
                colorFilter = ColorFilter.tint(colorScheme.onSurfaceVariantActions),
                modifier = Modifier.size(width = 10.dp, height = 16.dp),
            )
        },
        onFractionProgress = { state.fraction = it.fraction },
        dragSelectState = dragSelect,
    ) {
        LiquidDropdownColumn(
            onFitsOnScreen = { dragSelect.fitsOnScreen = it },
        ) {
            state.items.forEachIndexed { index, item ->
                LiquidDropdownRow(
                    text = item,
                    selected = index == state.selected,
                    onClick = {
                        state.onSelect(index)
                        state.close()
                    },
                    isFirst = index == 0,
                    isLast = index == state.items.lastIndex,
                )
            }
        }
    }
    }
}
