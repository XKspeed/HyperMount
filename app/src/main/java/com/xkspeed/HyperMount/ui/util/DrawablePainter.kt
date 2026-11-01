package com.xkspeed.HyperMount.ui.util

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * 用 Drawable 画的 Painter 专门用来显示 launcher 图标
 *
 * 不能用 `painterResource()`：自适应图标是 XML（adaptive-icon）
 * 它既不是 VectorDrawable 也不是位图 Compose 加载会抛
 * `IllegalArgumentException: Unable to load image` 页面一进就闪退
 *
 * 这里直接拿系统解析好的 Drawable 自己画 自适应图标 / 位图 / 矢量都通吃
 */
@Composable
fun rememberDrawablePainter(@DrawableRes id: Int): Painter? {
    val context = LocalContext.current
    return remember(id) {
        val drawable = ContextCompat.getDrawable(context, id) ?: return@remember null
        object : Painter() {
            override val intrinsicSize: Size
                get() = Size(
                    drawable.intrinsicWidth.coerceAtLeast(1).toFloat(),
                    drawable.intrinsicHeight.coerceAtLeast(1).toFloat(),
                )

            override fun DrawScope.onDraw() {
                drawIntoCanvas { canvas ->
                    drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                    drawable.draw(canvas.nativeCanvas)
                }
            }
        }
    }
}
