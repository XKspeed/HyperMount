// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package com.xkspeed.HyperMount.ui.component.effect

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.xkspeed.HyperMount.ui.util.isInDarkTheme
import com.xkspeed.HyperMount.ui.util.LocalIsWideScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.floor

@Composable
fun BgEffectBackground(
    dynamicBackground: Boolean,
    modifier: Modifier = Modifier,
    bgModifier: Modifier = Modifier,
    isFullSize: Boolean = false,
    effectBackground: Boolean = true,
    isOs3Effect: Boolean = true,
    // 把这一页的配色锁成和系统相反：系统深色时铺浅色流光 系统浅色时铺深色流光
    // 引导首页用它 点圆钮展开第二页时颜色正好翻回来 转场不用另做一套
    invertTheme: Boolean = false,
    alpha: () -> Float = { 1f },
    content: @Composable (BoxScope.() -> Unit),
) {
    val shaderSupported = remember { isRuntimeShaderSupported() }
    if (!shaderSupported) {
        Box(modifier = modifier, content = content)
        return
    }
    Box(
        modifier = modifier,
    ) {
        val systemDark = isInDarkTheme()
        val isDarkTheme = systemDark != invertTheme
        val surface = when {
            !invertTheme -> MiuixTheme.colorScheme.surface
            // 反色底：系统深色给近白 系统浅色给近黑 只当流光下面的垫色
            systemDark -> Color(0xFFF5F5F7)
            else -> Color(0xFF121216)
        }
        val deviceType = if (LocalIsWideScreen.current) DeviceType.PAD else DeviceType.PHONE
        val painter = remember(isOs3Effect) { BgEffectPainter(isOs3Effect) }

        val preset = remember(deviceType, isDarkTheme, isOs3Effect) {
            BgEffectConfig.get(deviceType, isDarkTheme, isOs3Effect)
        }

        val colorStage = remember { Animatable(0f) }

        LaunchedEffect(dynamicBackground, preset) {
            if (!dynamicBackground) return@LaunchedEffect
            val animatesColors = preset.colors1 !== preset.colors2 || preset.colors2 !== preset.colors3
            if (!animatesColors) return@LaunchedEffect

            var targetStage = floor(colorStage.value) + 1f
            while (isActive) {
                delay((preset.colorInterpPeriod * 500).toLong())
                colorStage.animateTo(
                    targetValue = targetStage,
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 35f),
                )
                targetStage += 1f
            }
        }

        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .then(bgModifier)
                .bgEffectDraw(
                    painter = painter,
                    preset = preset,
                    deviceType = deviceType,
                    isDarkTheme = isDarkTheme,
                    surface = surface,
                    effectBackground = effectBackground,
                    isFullSize = isFullSize,
                    playing = dynamicBackground,
                    colorStage = { colorStage.value },
                    alpha = alpha,
                ),
        )
        content()
    }
}
