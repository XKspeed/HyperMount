package com.xkspeed.HyperMount.ui.component.glass

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * 玻璃模糊强度倍率（0f..2f 1f = 原样） 界面 ›「玻璃效果」
 *
 * 只乘在**玻璃控件**的模糊半径上 口径集中在这里 新增玻璃控件时务必接上：
 * - [LiquidTopBarButton]（顶栏圆钮 含自适应亮度分支）
 * - [LiquidBottomTabs] 玻璃容器（非玻璃磨砂分支的 12dp 不乘）
 *
 * **不乘**：TitleBarProgressiveBlur 的渐进模糊、各处非玻璃磨砂回退分支
 *
 * 读取位置：小组合体在组合体内读（拖动滑块时当场重组）；体量过大的组合体
 * 在 `drawBackdrop` 的 effects 内读 —— 库对 effects 内的状态读取走 observeReads
 * 值变即重建 RenderEffect 链
 *
 * 倍率是 Compose 状态（[mutableFloatStateOf]）：拖动设置页滑块时玻璃控件当场变化
 * 不需要重启或重进页面 持久化由 [GlassEffectState.load]/[GlassEffectState.save] 负责
 */
val glassBlurScale: Float
    get() = GlassEffectState.blurScale

/**
 * 玻璃效果的可调状态（与 [glassBlurScale] 同源 这里放持久化）
 *
 * 单独一个 object 是为了让玻璃组件包不必反向依赖界面设置页；
 * 界面设置页（UiConfigState.load/save）负责在启动与改动时调用读写
 */
object GlassEffectState {
    /** 0f = 玻璃面不模糊（近乎透明） 1f = 原样 2f = 双倍模糊 */
    var blurScale by mutableFloatStateOf(1f)

    private const val PREFS = "glass_effect"
    private const val KEY_BLUR_SCALE = "blur_scale"

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        blurScale = p.getFloat(KEY_BLUR_SCALE, 1f).coerceIn(0f, 2f)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_BLUR_SCALE, blurScale)
            .apply()
    }
}
