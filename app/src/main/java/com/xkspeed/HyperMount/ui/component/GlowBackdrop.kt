package com.xkspeed.HyperMount.ui.component

import android.graphics.RuntimeShader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop

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
 * 用 Compose 的 drawBehind 直接画，不走 AndroidView：
 * 只有绘制发生在 Compose 的图层里，[backdrop] 才录得到这层光晕，
 * 字标才能像关于页那样用 textureBlur 从里面采样。
 *
 * 传 [backdrop] 时这层会同时被录进 backdrop（layerBackdrop），不传就只画。
 */
@Composable
fun GlowBackdrop(
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = null,
) {
    val context = LocalContext.current
    val shader = remember(context) {
        val src = context.assets.open("glow.glsl").bufferedReader().use { it.readText() }
        RuntimeShader(src).apply { applyBrandUniforms() }
    }

    // 每帧推进一次；只能在 draw 里读，读得到才会触发重绘
    var time by remember { mutableFloatStateOf(2f) }

    LaunchedEffect(Unit) {
        var last = 0L
        var dir = 1f
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    time = (time + (now - last) * 1.0e-9f * dir).let { t ->
                        when {
                            dir > 0f && t >= 120f -> { dir = -1f; 120f }
                            dir < 0f && t <= 2f -> { dir = 1f; 2f }
                            else -> t
                        }
                    }
                }
                last = now
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
            // 底色先铺黑：shader 的 alpha 恒为 1，这里只是防首帧闪白
            .background(Color.Black)
            .drawBehind {
                shader.setFloatUniform("uResolution", size.width, size.height)
                shader.setFloatUniform("uTime", time)
                drawRect(brush = ShaderBrush(shader))
            },
    )
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
