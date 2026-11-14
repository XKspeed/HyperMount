package com.xkspeed.HyperMount.ui.component.glass

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 底栏三个图标的尺寸与位置（dp 与纵向缩放）。
 *
 * 调试页里逐项调，**只在内存里**：进程重启回默认值。
 * 调定之后按调试页「导出 JSON」长按复制出来，把数值写进下面的 [defaults]。
 *
 * 布局占位永远是 [BASE_SIZE]，尺寸与位置全部走绘制层：
 * 调大不会把文字往下挤、也不会撑高胶囊，两种底栏（液态 / 标准）表现一致。
 *
 * 为什么要 `scaleY`：有些图标（比如 Android 剪影）本身比例偏扁，
 * 单纯放大尺寸会连带加宽，只有纵向拉伸才能把高度补回来。
 */
data class NavIconTune(
    val size: Float,
    val offsetX: Float,
    val offsetY: Float,
    val scaleY: Float,
)

object NavIconTuning {
    /** 底栏顺序：系统界面 / 应用 / 设置 */
    val keys = listOf("systemUi", "apps", "settings")
    val labels = listOf("系统界面", "应用", "设置")

    /** 图标在布局里固定占这么大 尺寸变化只缩放绘制内容 */
    const val BASE_SIZE = 26f

    const val MIN_SIZE = 18f
    const val MAX_SIZE = 34f
    const val SIZE_STEP = 1f

    const val MIN_OFFSET = -6f
    const val MAX_OFFSET = 6f
    const val OFFSET_STEP = 0.5f

    const val MIN_SCALE_Y = 0.60f
    const val MAX_SCALE_Y = 1.60f
    const val SCALE_STEP = 0.05f

    /** 定稿值：调试页里调好之后把这里改成导出 JSON 里的数 */
    private val defaults = listOf(
        NavIconTune(size = 24f, offsetX = 0f, offsetY = 0f, scaleY = 1f),
        // Android 剪影天生偏扁 纵向拉到 1.4 才和另外两个一样高
        NavIconTune(size = 30f, offsetX = 0f, offsetY = 0f, scaleY = 1.4f),
        NavIconTune(size = 26f, offsetX = 0f, offsetY = 0f, scaleY = 1f),
    )

    /** 第 index 项的默认档：滑条上的默认刻度、越界回退、单项重置都用它 */
    fun defaultTune(index: Int): NavIconTune = defaults.getOrElse(index) { defaults.last() }

    private val state = mutableStateListOf(*defaults.toTypedArray())

    /** 读值：返回列表本身，元素替换会触发重组 */
    val items: List<NavIconTune> get() = state

    // ---- 拖动中：只夹范围 不吸附（无极调节） ----

    fun setSize(index: Int, value: Float) {
        state[index] = state[index].copy(size = value.coerceIn(MIN_SIZE, MAX_SIZE))
    }

    fun setOffsetX(index: Int, value: Float) {
        state[index] = state[index].copy(offsetX = value.coerceIn(MIN_OFFSET, MAX_OFFSET))
    }

    fun setOffsetY(index: Int, value: Float) {
        state[index] = state[index].copy(offsetY = value.coerceIn(MIN_OFFSET, MAX_OFFSET))
    }

    fun setScaleY(index: Int, value: Float) {
        state[index] = state[index].copy(scaleY = value.coerceIn(MIN_SCALE_Y, MAX_SCALE_Y))
    }

    // ---- 松手：吸附到最近档位 ----

    fun snapSize(index: Int) {
        state[index] = state[index].copy(size = snap(state[index].size, SIZE_STEP))
    }

    fun snapOffsetX(index: Int) {
        state[index] = state[index].copy(offsetX = snap(state[index].offsetX, OFFSET_STEP))
    }

    fun snapOffsetY(index: Int) {
        state[index] = state[index].copy(offsetY = snap(state[index].offsetY, OFFSET_STEP))
    }

    fun snapScaleY(index: Int) {
        state[index] = state[index].copy(scaleY = snap(state[index].scaleY, SCALE_STEP))
    }

    fun reset(index: Int) {
        state[index] = defaults[index]
    }

    fun resetAll() {
        defaults.forEachIndexed { index, tune -> state[index] = tune }
    }

    /**
     * 导出成可直接照抄进 [defaults] 的 JSON
     *
     * 字段名与调试页滑条一一对应：size / x / y / scaleY
     */
    fun toJson(): String = buildString {
        append("{\n")
        keys.forEachIndexed { index, key ->
            val tune = state[index]
            append("  \"$key\": {")
            append("\"size\": ${fmt(tune.size)}, ")
            append("\"x\": ${fmt(tune.offsetX)}, ")
            append("\"y\": ${fmt(tune.offsetY)}, ")
            append("\"scaleY\": ${fmt(tune.scaleY)}")
            append("}")
            if (index != keys.lastIndex) append(",")
            append("\n")
        }
        append("}")
    }

    private fun snap(value: Float, step: Float): Float =
        (value / step).toInt().let { it * step }

    /** 26 / 1.2 / 1.15 这种去掉多余的 0，方便直接粘进代码 */
    private fun fmt(value: Float): String =
        if (value == value.toInt().toFloat()) {
            value.toInt().toString()
        } else {
            "%.2f".format(value).trimEnd('0').trimEnd('.')
        }
}

/**
 * 底栏图标统一按这套参数绘制
 *
 * 占位恒为 [NavIconTuning.BASE_SIZE]：`size` 折成等比缩放、`scaleY` 再叠一层纵向拉伸，
 * 位移用 dp 转 px 直接平移，全部发生在绘制阶段，不影响布局。
 */
fun Modifier.navIconTune(tune: NavIconTune): Modifier = this
    .size(NavIconTuning.BASE_SIZE.dp)
    .graphicsLayer {
        val scale = tune.size / NavIconTuning.BASE_SIZE
        scaleX = scale
        scaleY = scale * tune.scaleY
        translationX = tune.offsetX.dp.toPx()
        translationY = tune.offsetY.dp.toPx()
    }
