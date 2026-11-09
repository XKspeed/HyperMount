@file:OptIn(ExperimentalLayoutApi::class)

package com.xkspeed.HyperMount.ui.util

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * 布局避让用的系统栏高度：**不跟系统栏显隐走**
 *
 * 原生 `WindowInsets.statusBars` / `navigationBars` 是可见性感知的：
 * 切到多任务总览（或任何系统栏临时隐藏的场合）时值直接变 0，
 * 顶栏与大标题的避让一撤、底栏的避让一撤，整页会上下跳一下。
 *
 * 这里统一换成 `*IgnoringVisibility`（API 30+，本项目 minSdk 35）：
 * 它只随屏幕物理尺寸变，系统栏隐藏时仍返回原来的高度，布局因此保持不动。
 * App 内没有主动隐藏系统栏的场景，多留这一段不会露白。
 */
@Composable
fun stableStatusBarHeight(): Dp =
    WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()

@Composable
fun stableNavigationBarHeight(): Dp =
    WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()

/** 顶栏 / 大标题顶部避让：状态栏隐藏时不收回 */
@Composable
fun Modifier.stableStatusBarsPadding(): Modifier =
    this.padding(top = stableStatusBarHeight())

/** 底栏 / 悬浮条底部避让：手势条隐藏时不收回 */
@Composable
fun Modifier.stableNavigationBarsPadding(): Modifier =
    this.padding(bottom = stableNavigationBarHeight())
