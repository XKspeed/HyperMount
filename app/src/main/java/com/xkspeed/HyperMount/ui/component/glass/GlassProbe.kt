package com.xkspeed.HyperMount.ui.component.glass


/**
 * 玻璃材质的消融开关
 *
 * 原项目用这些位做逐项 A/B（关掉某层量帧时间） 值从系统 settings 读
 * 这里没有调参需求 全部固定为 true —— 也就是"该有的都有"
 * 保留这个结构是因为移植过来的绘制代码按这些位分支 硬删会把 600 行的
 * Modifier 链拆散 反而更容易出错
 */
internal object GlassProbe {
    val current: Bits = Bits()

    data class Bits(
        val navContainerSample: Boolean = true,
        val navContainerEffects: Boolean = true,
        val navContainerHighlight: Boolean = true,
        val navContainerShadow: Boolean = true,
        val navContainerLayer: Boolean = true,
    )
}
