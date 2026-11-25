package com.xkspeed.HyperMount.ui.component

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 引导页欢迎屏的发光底
 *
 * 移植自 HyperCeiler 的开机向导（AGPL-3.0）：`assets/glow.glsl` 是原作的
 * RuntimeShader 片元着色器，用两次 simplex 噪声叠一层随时间扩散的圆环，
 * 再按 Oklab 混色，得到软件层画不出的那团「有体积的辉光」。
 *
 * 和原作的两处差别：
 *  - 品牌色换成 HyperMount 的蓝（原作是红→蓝→紫，我们是深蓝→亮蓝→青），
 *    其余 uniform 全部沿用原作默认值
 *  - 时间不再无限增长，在 2～120 秒之间来回走（原作如此，防止浮点精度衰减）
 *
 * 每帧重建 RenderEffect 是原作的做法，只在欢迎屏出现时跑；切走即停。
 */
@Composable
fun GlowBackdrop(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shader = remember(context) {
        val src = context.assets.open("glow.glsl").bufferedReader().use { it.readText() }
        RuntimeShader(src).apply { applyBrandUniforms() }
    }
    val view = remember(context) {
        // 背景铺黑：shader 的 alpha 恒为 1，这里只是防首帧闪白
        View(context).apply { setBackgroundColor(android.graphics.Color.BLACK) }
    }

    AndroidView(factory = { view }, modifier = modifier.fillMaxSize())

    DisposableEffect(view) {
        var running = true
        var last = System.nanoTime()
        var time = 0f
        var dir = 1f

        val tick = object : Runnable {
            override fun run() {
                if (!running) return
                val now = System.nanoTime()
                time += (now - last) * 1.0e-9f * dir
                last = now
                if (dir > 0f && time >= 120f) dir = -1f
                if (dir < 0f && time <= 2f) dir = 1f

                val w = view.width.coerceAtLeast(1).toFloat()
                val h = view.height.coerceAtLeast(1).toFloat()
                shader.setFloatUniform("uTime", time)
                shader.setFloatUniform("uResolution", w, h)
                view.setRenderEffect(RenderEffect.createShaderEffect(shader))

                view.postDelayed(this, 16L)
            }
        }
        view.post(tick)

        onDispose {
            running = false
            view.removeCallbacks(tick)
            view.setRenderEffect(null)
        }
    }
}

/**
 * 一次性写入全部 uniform
 *
 * 除三个品牌色外都是原作 `GlowPainter` 的默认值，改动任何一个都会让
 * 光斑的形状、扩散速度、圆环粗细跑偏，所以集中放在这里，别散着调。
 */
private fun RuntimeShader.applyBrandUniforms() {
    // ---- 品牌色：深蓝 → 亮蓝 → 青，对齐图标的白环 + 蓝底 ----
    setFloatUniform("uColorBlack", 0.10f, 0.22f, 0.72f)
    setFloatUniform("uColorMid", 0.36f, 0.56f, 0.97f)
    setFloatUniform("uColorWhite", 0.15f, 0.68f, 0.92f)

    // ---- 灰度层（第二层噪声，负责明暗纹理）----
    setFloatUniform("uScale", 1.3f)
    setFloatUniform("uSpeed", 0.4f)
    setFloatUniform("uBrightnessInMin", 0.25f)
    setFloatUniform("uBrightnessInMax", 1.0f)
    setFloatUniform("uBrightnessOutMin", 0.25f)
    setFloatUniform("uBrightnessOutMax", 1.0f)

    // ---- 彩色层（第一层噪声，负责色相分布）----
    setFloatUniform("uScale2", 0.82f)
    setFloatUniform("uSpeed2", 0.49f)
    setFloatUniform("uColorInMin", 0.3f)
    setFloatUniform("uColorInMax", 1.0f)
    setFloatUniform("uColorOutMin", 0.3f)
    setFloatUniform("uColorOutMax", 0.86f)
    setFloatUniform("uColorMidPoint", 0.47f)
    setFloatUniform("uUseOklab", 1.0f)

    // ---- 开场圆环：从中心扩散，边扩散边变形 ----
    setFloatUniform("uShowCircle", 1.0f)
    setFloatUniform("uCircleThickness", 0.4f)
    setFloatUniform("uCircleFinalRadius", 1.0f)
    setFloatUniform("uCircleYOffset", 0.1f)
    setFloatUniform("uCircleSpeed", 0.9f)
    setFloatUniform("uCircleColorFreq", 1.0f)
    setFloatUniform("uCircleColorSpeed", 0.0f)
    setFloatUniform("uCircleEasing", 1.4f)
    setFloatUniform("uCircleAnimationOffset", 0.0f)
    setFloatUniform("uCircleScreenBlend", 1.0f)
    setFloatUniform("uCircleAddBlend", 0.04f)
    setFloatUniform("uCircleColorOffset", 0.25f)
    setFloatUniform("uCircleUVDistort", 0.0f)
    setFloatUniform("uColorToDistortWidthRatio", 0.6f)
    setFloatUniform("uDistortStartTime", 0.2f)
    setFloatUniform("uDistortEndTime", 0.3f)
    setFloatUniform("uDistortStart", 0.0f)
    setFloatUniform("uDistortEnd", 1.0f)

    // ---- 条纹扰动：0 表示关掉（原作也没开）----
    setFloatUniform("uStripeFrequency", 0.0f)
    setFloatUniform("uStripeStrengthX", 0.0f)
    setFloatUniform("uStripeStrengthY", 0.0f)
    setFloatUniform("uStripeUVDistort", 0.0f)

    // ---- 开场遮罩：从中心往外揭开整个画面 ----
    setFloatUniform("uMaskDelay", 0.3f)
    setFloatUniform("uMaskThickness", 0.3f)
}
