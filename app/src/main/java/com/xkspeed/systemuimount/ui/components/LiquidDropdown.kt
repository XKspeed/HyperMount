package com.xkspeed.systemuimount.ui.components

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
import com.xkspeed.systemuimount.ui.component.glass.glassBlurScale
import com.xkspeed.systemuimount.ui.util.isInDarkTheme
import kotlin.math.ceil
import kotlin.math.min
import kotlinx.coroutines.Job
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
// 动画参数（与 Sonify 一致，逐值对齐）
// =====================================================================

private val FractionEnterAnimSpec =
    spring<Float>(dampingRatio = 0.78f, stiffness = 240f, visibilityThreshold = 0.0001f)
private val FractionExitAnimSpec =
    spring<Float>(dampingRatio = 0.78f, stiffness = 400f, visibilityThreshold = 0.0001f)
private val AlphaEnterAnimSpec = tween<Float>(durationMillis = 120)
private val AlphaExitAnimSpec = tween<Float>(durationMillis = 400)

private val OriginEnterAnimSpec =
    spring<Float>(dampingRatio = 0.78f, stiffness = 500f, visibilityThreshold = 0.0001f)
private val OriginExitAnimSpec = tween<Float>(durationMillis = 450)

private val PopupMinWidth = 200.dp
private val PopupMaxWidth = 288.dp
private val PopupMinHeight = 50.dp
private val PopupCornerRadius = 25.dp
private val PopupShadowPadding = 24.dp
private val DropdownButtonDiameter = 42.dp

private const val ScaleBase = 0.24f

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
 * 右缘对齐锚点行右缘内收 27dp，默认压在锚点行上方 31dp，下方放不下翻到上方，
 * 都不够时垂直居中，最终 clamp 进窗口。
 */
private fun Density.calculateDropdownPosition(
    anchorBounds: IntRect,
    windowBounds: IntRect,
    outerSize: IntSize,
    visibleSize: IntSize,
): LiquidDropdownPositionResult {
    val offsetDeltaX = 27.dp.roundToPx()
    val offsetDeltaY = 31.dp.roundToPx()

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
    onFractionProgress: ((LiquidDropdownProgress) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val fractionProgress = remember { Animatable(0f) }
    val originProgress = remember { Animatable(0f) }
    val alphaProgress = remember { Animatable(0f) }
    var internalVisible by remember { mutableStateOf(false) }
    var layoutPosition by remember { mutableStateOf(LiquidDropdownLayoutPosition()) }
    var anchorOrigin by remember { mutableStateOf(TransformOrigin(1f, 0f)) }
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val currentOnDismissFinished by rememberUpdatedState(onDismissFinished)
    val currentOnFractionProgress by rememberUpdatedState(onFractionProgress)
    val bottomOverlayPx = with(LocalDensity.current) { LocalBottomOverlayHeight.current.toPx() }

    // 三轨并行：fraction 管缩放/裁剪、origin 管锚点从角移到中心、alpha 管整体淡入淡出
    LaunchedEffect(expanded) {
        if (expanded) {
            internalVisible = true
            launch { fractionProgress.animateTo(1f, FractionEnterAnimSpec) }
            launch { originProgress.animateTo(1f, OriginEnterAnimSpec) }
            launch { alphaProgress.animateTo(1f, AlphaEnterAnimSpec) }
        } else {
            if (!internalVisible) return@LaunchedEffect
            launch { fractionProgress.animateTo(0f, FractionExitAnimSpec) }
            launch { originProgress.animateTo(0f, OriginExitAnimSpec) }
            alphaProgress.animateTo(0f, AlphaExitAnimSpec)
            fractionProgress.snapTo(0f)
            originProgress.snapTo(0f)
            alphaProgress.snapTo(0f)
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
                    // 判断方向用可见面板高度，含阴影外扩会把高菜单误判成"下方放不下"
                    val visibleSize = IntSize(
                        (outerSize.width - 2 * padPx).coerceAtLeast(1),
                        (outerSize.height - 2 * padPx).coerceAtLeast(1),
                    )
                    val result = calculateDropdownPosition(
                        anchorBounds, windowBounds, outerSize, visibleSize,
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
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(
                            IntOffset(
                                x = result.offset.x - scrimOrigin.x,
                                y = result.offset.y - scrimOrigin.y,
                            ),
                        )
                    }
                },
        ) {
            LiquidDropdownContent(
                fractionProgress = { fractionProgress.value },
                originProgress = { originProgress.value },
                alphaProgress = { alphaProgress.value },
                layoutPosition = layoutPosition,
                originAnchor = anchorOrigin,
                backdrop = backdrop,
                content = content,
            )
        }
    }
}

@Composable
private fun LiquidDropdownContent(
    fractionProgress: () -> Float,
    originProgress: () -> Float,
    alphaProgress: () -> Float,
    layoutPosition: LiquidDropdownLayoutPosition,
    originAnchor: TransformOrigin,
    backdrop: Backdrop?,
    content: @Composable () -> Unit,
) {
    val isDark = isInDarkTheme()
    val containerColor = if (isDark) Color(0xFF242424) else Color.White
    val glassSurfaceAlpha = if (isDark) 0.8f else 0.72f
    val fallbackSurfaceAlpha = 0.97f
    val edgeLightColor =
        if (!isDark) Color.White.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.4f)
    val density = LocalDensity.current

    // 阴影：进入 fraction≥0.78 渐显（tween 200），退出 ≥0.99 消失（tween 50）
    val shadowAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        var prevFraction = fractionProgress()
        var shadowVisible = false
        var animationJob: Job? = null
        snapshotFlow { fractionProgress() }
            .collect { current ->
                val isEntering = current >= prevFraction
                prevFraction = current
                val newVisible = if (isEntering) current >= 0.78f else current >= 0.99f
                if (newVisible != shadowVisible) {
                    shadowVisible = newVisible
                    animationJob?.cancel()
                    animationJob = launch {
                        if (newVisible) {
                            shadowAlpha.animateTo(1f, tween(200))
                        } else if (shadowAlpha.value >= 1f) {
                            shadowAlpha.animateTo(0f, tween(50))
                        } else {
                            shadowAlpha.snapTo(0f)
                        }
                    }
                }
            }
    }

    // 内容透明度：进场 0.2+0.8f，退出过半后 f*2 加速消失（壳先走、内容后散）
    val contentAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        var prevFraction = 0f
        snapshotFlow { fractionProgress() }
            .collect { current ->
                val isEntering = current >= prevFraction
                prevFraction = current
                contentAlpha.snapTo(
                    if (isEntering) 0.2f + 0.8f * current
                    else if (current > 0.5f) 1f else current * 2f,
                )
            }
    }

    Box(
        modifier = Modifier
            .padding(PopupShadowPadding)
            .drawBehind {
                val alpha = shadowAlpha.value
                if (alpha <= 0f) return@drawBehind
                val baseAlpha = (32 * alpha).toInt().coerceIn(0, 255)
                val blurRadius = 16f * this.density
                val nativePath = android.graphics.Path().apply {
                    addRoundRect(
                        0f, 0f, size.width, size.height,
                        PopupCornerRadius.toPx(), PopupCornerRadius.toPx(),
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
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val fraction = fractionProgress()
                    val scale = ScaleBase + (1f - ScaleBase) * fraction
                    scaleX = scale
                    scaleY = scale
                    alpha = alphaProgress()
                    // 锚点从角插值到中心，走独立的 origin 曲线
                    transformOrigin = TransformOrigin(
                        pivotFractionX = originAnchor.pivotFractionX +
                                (0.5f - originAnchor.pivotFractionX) * originProgress(),
                        pivotFractionY = originAnchor.pivotFractionY +
                                (0.5f - originAnchor.pivotFractionY) * originProgress(),
                    )
                }
                .dropdownClipReveal(
                    fractionProgress = fractionProgress,
                    pivotX = originAnchor.pivotFractionX,
                    showBelow = layoutPosition.showBelow,
                    showAbove = layoutPosition.showAbove,
                )
                .contentBlur(radius = (8f * (1f - fractionProgress())).dp)
                .then(
                    if (backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = {
                                // 圆角随缩放反向放大，视觉圆角保持不变
                                val avgScale = ScaleBase + (1f - ScaleBase) * fractionProgress()
                                RoundedCornerShape(
                                    CornerSize(
                                        with(density) {
                                            (PopupCornerRadius.toPx() / avgScale).toDp()
                                        },
                                    ),
                                )
                            },
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
                        Modifier.background(
                            containerColor.copy(alpha = fallbackSurfaceAlpha),
                            shape = RoundedCornerShape(PopupCornerRadius),
                        )
                    },
                )
                .rectEdgeLight(
                    shapeProvider = {
                        val avgScale = ScaleBase + (1f - ScaleBase) * fractionProgress()
                        RoundedCornerShape((PopupCornerRadius.toPx() / avgScale).toDp())
                    },
                    color = edgeLightColor,
                    strokeWidth = 0.28f.dp,
                    blurRadius = 0.8f.dp,
                ),
        ) {
            Box(modifier = Modifier.graphicsLayer { alpha = contentAlpha.value }) {
                content()
            }
        }
    }
}

/**
 * 裁剪揭示：可见区域从「被按控件大小」的方块沿锚点角双向长成完整面板。
 */
private fun Modifier.dropdownClipReveal(
    fractionProgress: () -> Float,
    pivotX: Float,
    showBelow: Boolean,
    showAbove: Boolean,
): Modifier = drawWithCache {
    val path = Path()
    onDrawWithContent {
        val progress = fractionProgress().coerceIn(0f, 1f)
        if (progress <= 0f) return@onDrawWithContent

        val scale = ScaleBase + (1f - ScaleBase) * progress
        val buttonPx = DropdownButtonDiameter.toPx()
        val targetWidth = buttonPx + (size.width - buttonPx) * progress
        val targetHeight = buttonPx + (size.height - buttonPx) * progress
        val clipWidth = (targetWidth / scale).coerceAtMost(size.width)
        val clipHeight = (targetHeight / scale).coerceAtMost(size.height)

        val left = when {
            pivotX >= 0.99f -> size.width - clipWidth
            pivotX <= 0.01f -> 0f
            else -> (size.width - clipWidth) / 2f
        }
        val top = when {
            showBelow -> 0f
            showAbove -> size.height - clipHeight
            else -> (size.height - clipHeight) / 2f
        }

        val outline = RoundedCornerShape(PopupCornerRadius.toPx() / scale)
            .createOutline(
                size = Size(clipWidth, clipHeight),
                layoutDirection = layoutDirection,
                density = this@drawWithCache,
            )
        path.rewind()
        when (outline) {
            is Outline.Rounded -> path.addRoundRect(outline.roundRect)
            is Outline.Generic -> path.addPath(outline.path)
            is Outline.Rectangle -> path.addRect(outline.rect)
        }
        translate(left = left, top = top) {
            clipPath(path) {
                translate(left = -left, top = -top) {
                    this@onDrawWithContent.drawContent()
                }
            }
        }
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

@Composable
fun LiquidDropdownColumn(
    modifier: Modifier = Modifier,
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
    Row(
        modifier = modifier
            .padding(
                start = 8.dp,
                end = 8.dp,
                top = if (isFirst) 8.dp else 0.dp,
                bottom = if (isLast) 8.dp else 0.dp,
            )
            .clip(RoundedCornerShape(17.dp))
            // 点击光晕用主题给的 Indication（Miuix 那套 spring 按压高亮），
            // 跟卡片里的 ArrowPreference / SwitchPreference 完全同一手感
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
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
 * 行尾内容在弹层展开时淡出（Sonify 设置页 SelectItem 同参数）：
 * 出现时 fraction<0.15 才显示、关闭时 <0.2 恢复，tween(180)，基准 alpha 0.4。
 */
@Composable
private fun rememberRowContentAlpha(fraction: () -> Float): Float {
    val contentAlpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        var prevFraction = 0f
        var contentVisible = true
        var animJob: Job? = null
        snapshotFlow { fraction() }
            .collect { current ->
                val isEntering = current >= prevFraction
                prevFraction = current
                val newVisible = if (isEntering) current < 0.15f else current < 0.2f
                if (newVisible != contentVisible) {
                    contentVisible = newVisible
                    animJob?.cancel()
                    animJob = launch {
                        contentAlpha.animateTo(
                            targetValue = if (newVisible) 1f else 0f,
                            animationSpec = tween(180),
                        )
                    }
                }
            }
    }
    return contentAlpha.value
}

/** 下拉选择项的共享状态：页面级持有，行与弹层各拿一半 */
class LiquidSelectState {
    internal var expanded by mutableStateOf(false)
    internal var anchorBounds by mutableStateOf(IntRect.Zero)
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
    val isOpen = state.expanded && state.owner === rowKey
    val rowAlpha = rememberRowContentAlpha { if (state.owner === rowKey) state.fraction else 0f }
    // 行尾「当前值 + 箭头」的颜色也跟主题走：onSurfaceVariantActions 在非莫奈模式下
    // 本身只有 40% 不透明度 再乘下面那层 0.4 会淡到看不见
    val actionColor = if (enabled) colorScheme.onSurface
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
                modifier = Modifier.graphicsLayer { alpha = 0.4f * rowAlpha },
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
    if (!state.expanded && state.fraction <= 0f) return
    androidx.compose.runtime.CompositionLocalProvider(
        LocalBottomOverlayHeight provides bottomInset,
    ) {
    LiquidDropdownLayout(
        expanded = state.expanded,
        anchorBounds = state.anchorBounds,
        onDismissRequest = { state.close() },
        backdrop = backdrop,
        onFractionProgress = { state.fraction = it.fraction },
    ) {
        LiquidDropdownColumn {
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
