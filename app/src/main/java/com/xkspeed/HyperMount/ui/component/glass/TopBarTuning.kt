package com.xkspeed.HyperMount.ui.component.glass

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * 顶栏按钮的图标尺寸与位置（dp）。
 *
 * 目前只作用于右上角那个动作键（设置页的主题入口）——左上角返回键还写死
 * 18dp + 左移 2dp，那是照 Sonify 的箭头调的，不跟着这几个值变。
 *
 * 位置单独可调是因为 Miuix 的 Theme 图标本身重心偏上，摆在 42dp 圆钮正中
 * 看着头重脚轻，得往下压一点才像居中。
 *
 * **只在内存里**：调试页进得去才有意义 进程重启就回默认值。
 * 不写 SharedPreferences 是因为正式包没有调试入口 存了也没人读
 * 反而要给「默认档位变了 旧值怎么作废」这种问题兜底。
 * 真机调定的档位直接写进下面的默认常量。
 */
object TopBarTuning {
    /** 默认值：真机上调定的档位 */
    const val DEFAULT_ACTION_ICON_SIZE = 26f

    /** 默认垂直位移：Theme 图标重心偏上 往下 0.5dp 视觉才正 */
    const val DEFAULT_ACTION_OFFSET_Y = 0.5f

    const val MIN_ACTION_ICON_SIZE = 16f
    const val MAX_ACTION_ICON_SIZE = 30f

    /** 偏移范围对称正负 6dp，半 dp 一档 */
    const val MIN_ACTION_OFFSET = -6f
    const val MAX_ACTION_OFFSET = 6f
    const val ACTION_OFFSET_STEP = 0.5f

    /** 右上角动作键的图标边长 */
    var actionIconSize by mutableFloatStateOf(DEFAULT_ACTION_ICON_SIZE)
        private set

    /** 图标在圆钮里的水平位移 正数向右 */
    var actionOffsetX by mutableFloatStateOf(0f)
        private set

    /** 图标在圆钮里的垂直位移 正数向下 */
    var actionOffsetY by mutableFloatStateOf(DEFAULT_ACTION_OFFSET_Y)
        private set

    fun setSize(size: Float) {
        actionIconSize = size.coerceIn(MIN_ACTION_ICON_SIZE, MAX_ACTION_ICON_SIZE)
    }

    fun setOffsetX(offset: Float) {
        actionOffsetX = snapOffset(offset)
    }

    fun setOffsetY(offset: Float) {
        actionOffsetY = snapOffset(offset)
    }

    fun reset() {
        actionIconSize = DEFAULT_ACTION_ICON_SIZE
        actionOffsetX = 0f
        actionOffsetY = DEFAULT_ACTION_OFFSET_Y
    }

    /** 夹到范围内并取到 0.5 的整数倍 输入框手填 1.2 之类也能落到档位上 */
    private fun snapOffset(offset: Float): Float {
        val clamped = offset.coerceIn(MIN_ACTION_OFFSET, MAX_ACTION_OFFSET)
        return (clamped / ACTION_OFFSET_STEP).toInt().let { it * ACTION_OFFSET_STEP }
    }
}
