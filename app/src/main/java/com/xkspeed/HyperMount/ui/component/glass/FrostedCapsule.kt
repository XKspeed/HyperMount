package com.xkspeed.HyperMount.ui.component.glass

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.xkspeed.HyperMount.ui.util.isInDarkTheme

/**
 * 底栏同款磨砂胶囊材质（关玻璃分支）
 *
 * 直接复用 [LiquidBottomTabs] 里 solidCapsule 那一套数值，两者看起来才是同一块料：
 * - 表面色 浅色 0.85 白 / 深色 0.55 的 #1C1C1E，12dp 背景模糊 + Shadow.Default
 * - 黑色环绕高光（WrapHighlightStyle）：这就是底栏那条细细的描边，缺了它卡片会显得发灰发脏
 * - 白提亮高光 + 上下内阴影延伸：叠在 emptyBackdrop 上单独一层，与底栏共用同一配方
 *
 * 不用默认 highlight：库的 DefaultHighlight 是一圈偏白的边，深色下会画成亮灰描边，
 * 和底栏的黑色描边正好相反。
 */
@Composable
fun Modifier.frostedCapsule(
    backdrop: Backdrop,
    shape: Shape = Capsule(),
): Modifier {
    val isLightTheme = !isInDarkTheme()
    val surfaceColor = if (isLightTheme) {
        Color.White.copy(alpha = 0.85f)
    } else {
        Color(0xFF1C1C1E).copy(alpha = 0.55f)
    }
    // 白高光与内阴影延伸：与 LiquidBottomTabs.containerHighlightOverlay 完全同参数
    val glowColor = Color.White.copy(alpha = if (isLightTheme) 0.2f else 0.1f)
    val highlightOverlay = Modifier.drawBackdrop(
        backdrop = emptyBackdrop(),
        shape = { shape },
        effects = {},
        highlight = {
            Highlight.Default.copy(
                width = 1.dp,
                blurRadius = if (isLightTheme) 0.1.dp else 0.5.dp,
                alpha = if (isLightTheme) 0.8f else 1f,
                style = HighlightStyle.Default(
                    color = Color.White.copy(alpha = if (isLightTheme) 0.2f else 0.1f),
                    blendMode = BlendMode.Plus,
                    angle = 90f,
                    falloff = 1f,
                ),
            )
        },
        shadow = { null },
        innerShadow = { null },
        onDrawBackdrop = {},
        onDrawSurface = { drawGlowBands(glowColor) },
    )
    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = { blur(12.dp.toPx()) },
            highlight = {
                Highlight.Default.copy(
                    width = if (isLightTheme) 0.5.dp else 0.4.dp,
                    blurRadius = if (isLightTheme) 0.2.dp else 0.1.dp,
                    alpha = 1f,
                    style = WrapHighlightStyle(
                        color = Color.Black.copy(alpha = if (isLightTheme) 0.5f else 0.4f),
                        blendMode = BlendMode.SrcOver,
                        angle = 0f,
                        falloff = 0.9f,
                        baseline = 0.4f,
                    ),
                )
            },
            shadow = { Shadow.Default },
            innerShadow = { null },
            onDrawSurface = { drawRect(surfaceColor) },
        )
        .then(highlightOverlay)
}

/** 上下各一条 6dp 白色内渐变带：顶边向下淡出、底边向上淡出，Plus 叠加 */
private fun DrawScope.drawGlowBands(glowColor: Color) {
    val glowH = 6.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(
            0f to glowColor,
            1f to Color.Transparent,
            startY = 0f,
            endY = glowH,
        ),
        size = Size(size.width, glowH),
        blendMode = BlendMode.Plus,
    )
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent,
            1f to glowColor,
            startY = size.height - glowH,
            endY = size.height,
        ),
        topLeft = Offset(0f, size.height - glowH),
        size = Size(size.width, glowH),
        blendMode = BlendMode.Plus,
    )
}
