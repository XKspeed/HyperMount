package com.xkspeed.HyperMount.ui.component.glass

import android.graphics.BlurMaskFilter
import android.graphics.Path
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.xkspeed.HyperMount.ui.util.isInDarkTheme
import kotlin.math.ceil

/** 非自适应路径的稳定单例 onDrawBackdrop（与 LiquidTopBarButton 同款） */
private val DefaultPillOnDrawBackdrop: androidx.compose.ui.graphics.drawscope.DrawScope.(
    drawBackdrop: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit
) -> Unit = { it() }

/**
 * 可拉伸的玻璃圆钮（[LiquidTopBarButton] 的胶囊版）
 *
 * 材质与顶栏那颗圆钮完全一致（vibrancy + blur + lens + 白色边缘光），
 * 区别只在形状：圆角恒等于高度的一半，所以宽度等于高度时是正圆，
 * 拉长以后两个端头仍是完整的圆、中间那段直接连成一条 —— 边缘光走的就是
 * 这条外轮廓，两个圆接合处的内侧不会有第二条弧线。
 *
 * 高度由调用方定，宽度由内容决定（也可以自己传 `Modifier.width`）。
 *
 * @param glassAlpha 整块玻璃（含边缘光与底色）的透明度，做淡入淡出用
 */
@Composable
fun LiquidGlassPill(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    height: Dp = 42.dp,
    glassAlpha: () -> Float = { 1f },
    content: @Composable BoxScope.() -> Unit,
) {
    val isLightTheme = !isInDarkTheme()
    // 与圆钮同一口径：只由主题决定，淡出统一交给 layerBlock 的 alpha
    val containerColor = if (isLightTheme) {
        Color(0xFFFFFFFF).copy(0.76f)
    } else {
        Color(0xFF242424).copy(0.84f)
    }
    val edgeLightColor = if (isLightTheme) {
        Color.White.copy(alpha = 0.8f)
    } else {
        Color.White.copy(alpha = 0.32f)
    }
    val glassScale = glassBlurScale

    Box(
        modifier
            .height(height)
            // 裁到胶囊里：调用方拉长/收窄时超出部分不能画到形状外面
            .clip(RoundedCornerShape(50)),
    ) {
        // 玻璃与内容分两层：layerBlock 的 alpha 作用在整块图层上，
        // 和内容放同一个节点的话淡出时图标会跟着一起消失
        Box(
            Modifier
                .matchParentSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    // 50% 圆角 = 胶囊：高度决定半径，宽度只影响直线段长度
                    shape = { RoundedCornerShape(50) },
                    effects = {
                        vibrancy()
                        blur(4.dp.toPx() * glassScale)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            lens(8f.dp.toPx(), 24f.dp.toPx())
                        }
                    },
                    highlight = { null },
                    shadow = { null },
                    layerBlock = { alpha = glassAlpha() },
                    onDrawBackdrop = DefaultPillOnDrawBackdrop,
                    onDrawSurface = { drawRect(containerColor) },
                )
                .pillEdgeLight(
                    color = edgeLightColor,
                    width = 0.15f.dp,
                    blurRadius = 0.5f.dp,
                    alpha = glassAlpha,
                ),
        )
        content()
    }
}

/**
 * 胶囊的边缘光：与 [circleEdgeLight] 同参数、同强度，只是描边换成圆角矩形
 *
 * 圆角半径取高度的一半 —— 拉长时两端仍是正圆、上下两条边直接连过去，
 * 两个圆相接的内侧自然没有弧线可画（"中间连接处不描边"就是这条外轮廓的结果）
 */
internal fun Modifier.pillEdgeLight(
    color: Color,
    width: Dp,
    blurRadius: Dp,
    alpha: () -> Float = { 1f },
): Modifier = drawWithContent {
    drawContent()
    val a = alpha()
    if (color.alpha <= 0f || width <= 0.dp || a <= 0.01f) return@drawWithContent
    val radius = size.height / 2f
    val strokePx = ceil(width.toPx().coerceAtMost(radius)) * 2f
    val blurPx = blurRadius.toPx()
    val path = Path().apply {
        addRoundRect(0f, 0f, size.width, size.height, radius, radius, Path.Direction.CW)
    }
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
            this.color = color.copy(alpha = color.alpha * a).toArgb()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = strokePx
            if (blurPx > 0f) {
                maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
            }
            xfermode = android.graphics.PorterDuffXfermode(
                if (color.red + color.green + color.blue < 1.5f) {
                    android.graphics.PorterDuff.Mode.SRC_OVER
                } else {
                    android.graphics.PorterDuff.Mode.ADD
                }
            )
        }
        canvas.save()
        canvas.nativeCanvas.clipPath(path)
        canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint)
        canvas.restore()
    }
}
