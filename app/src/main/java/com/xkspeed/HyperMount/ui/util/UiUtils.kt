@file:OptIn(ExperimentalLayoutApi::class)

package com.xkspeed.HyperMount.ui.util

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.utils.overScrollVertical
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.captionBarIgnoringVisibility

/** 宽屏（平板 / 折叠展开）标记 由 App 根部提供 */
val LocalIsWideScreen: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/**
 * 模糊背景板
 *
 * 必须在内容外层套 [androidx.compose.ui.draw.drawWithContent] 的 backdrop 里
 * 才能拿到"顶栏下面那层"的像素；这里只是创建 真正的采样靠调用方的 layerBackdrop
 */
@Composable
fun rememberBlurBackdrop(): LayerBackdrop? {
    if (!isRuntimeShaderSupported() || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return null
    }
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

/** 列表滚动手感：滚到底回弹 + 边缘过滚动 + 把滚动事件交给顶栏 */
fun Modifier.pageScrollModifiers(
    showTopAppBar: Boolean,
    topAppBarScrollBehavior: ScrollBehavior,
): Modifier = this
    .scrollEndHaptic()
    .overScrollVertical()
    .then(
        if (showTopAppBar) {
            Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
        } else {
            Modifier
        }
    )

/**
 * 页面内容留白 = 自身 Scaffold 的 insets + 外层（底栏）的 padding
 *
 * 之前漏掉 outerPadding 的 bottom 列表最后一项会被底栏压住 —— 就是这个函数的意义
 */
@Composable
fun pageContentPadding(
    innerPadding: PaddingValues,
    outerPadding: PaddingValues,
    isWideScreen: Boolean,
    extraTop: Dp = 0.dp,
    extraStart: Dp = 0.dp,
    extraEnd: Dp = 0.dp,
    extraBottom: Dp = 0.dp,
): PaddingValues {
    val topPadding = innerPadding.calculateTopPadding() + extraTop
    val bottomPadding = if (isWideScreen) {
        outerPadding.calculateBottomPadding() + extraBottom +
            stableNavigationBarHeight() +
            WindowInsets.captionBarIgnoringVisibility.asPaddingValues().calculateBottomPadding()
    } else {
        outerPadding.calculateBottomPadding() + extraBottom
    }
    return remember(topPadding, bottomPadding, extraStart, extraEnd) {
        PaddingValues(
            top = topPadding,
            start = extraStart,
            end = extraEnd,
            bottom = bottomPadding,
        )
    }
}

/** 手机竖屏 = 单栏；平板 / 折叠展开 = 宽屏 */
@Composable
fun shouldShowSplitPane(): Boolean {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    return with(density) {
        val widthDp = windowInfo.containerSize.width.toDp()
        val heightDp = windowInfo.containerSize.height.toDp()
        val ratio = heightDp / widthDp
        widthDp >= 840.dp || (widthDp >= 600.dp && ratio < 1.2f)
    }
}

/** 当前是不是深色主题：用 surface 亮度判断 比读系统配置可靠 */
@Composable
@ReadOnlyComposable
fun isInDarkTheme(): Boolean {
    val surface = MiuixTheme.colorScheme.surface
    val luminance = 0.2126f * surface.red + 0.7152f * surface.green + 0.0722f * surface.blue
    return luminance < 0.5f
}
