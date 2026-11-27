package com.xkspeed.HyperMount.ui.component.oobe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode

/**
 * OOBE 首屏光晕与混色的可调参数
 *
 * 只在调试页里活着，**纯内存**：进程重启回默认值，进 OOBE 预览时保留当前值。
 * 调定之后把数值搬回这里的默认常量（[Def]），再把调试页这一段撤掉。
 */
object OobeTuning {

    // ---- 光晕 ----

    /**
     * 从**图标入场动画开始**再等多久才扩（ms）
     *
     * 0 表示和图标缩放、淡入同时起，默认就是同时。
     */
    var delayMs by mutableFloatStateOf(Def.DELAY_MS)

    /** 扩到满屏用的时长（ms） */
    var durationMs by mutableFloatStateOf(Def.DURATION_MS)

    /** 外径 = 屏高 × 这个系数 × 进度 */
    var outerScale by mutableFloatStateOf(Def.OUTER_SCALE)

    /** 环带厚度 = 屏高 × 这个值 */
    var thickness by mutableFloatStateOf(Def.THICKNESS)

    /** 缓动 k：进度走 t/(k − t·(k−1))，越大起手越慢、末尾越快 */
    var easeK by mutableFloatStateOf(Def.EASE_K)

    /** 采样背景时的模糊半径（dp） */
    var blurRadius by mutableFloatStateOf(Def.BLUR_RADIUS)

    /** 环上再叠的那层白，峰值不透明度；0 表示只显示采样的底色 */
    var highlightAlpha by mutableFloatStateOf(Def.HIGHLIGHT_ALPHA)

    /** 从哪个进度开始淡出；1 表示不淡出 */
    var fadeStart by mutableFloatStateOf(Def.FADE_START)

    // ---- 混色 ----

    /** 三条色的混合模式：和 [Def] 里的默认三条一一对应，调试页只改颜色不改模式 */
    private val darkModes: List<BlurBlendMode> = listOf(
        BlurBlendMode.ColorDodge,
        BlurBlendMode.LinearLight,
        BlurBlendMode.Lab,
    )

    /** 浅底用 ColorBurn：底色亮，得往下压 */
    private val lightModes: List<BlurBlendMode> = listOf(
        BlurBlendMode.ColorBurn,
        BlurBlendMode.LinearLight,
        BlurBlendMode.Lab,
    )

    fun modes(onDark: Boolean): List<BlurBlendMode> = if (onDark) darkModes else lightModes

    /** 每条色的名字，只用于调试页标题 */
    fun modeNames(onDark: Boolean): List<String> =
        if (onDark) listOf("ColorDodge", "LinearLight", "Lab")
        else listOf("ColorBurn", "LinearLight", "Lab")

    private val darkColors = mutableStateListOf(
        Color(0xe6a1a1a1),
        Color(0x4de6e6e6),
        Color(0xff1af500),
    )

    private val lightColors = mutableStateListOf(
        Color(0xcc4a4a4a),
        Color(0xff4f4f4f),
        Color(0xff1af200),
    )

    private fun bucket(onDark: Boolean) = if (onDark) darkColors else lightColors

    /** 深底（提亮那组）/ 浅底（压暗那组）各三条，顺序与 [modes] 对齐 */
    fun colors(onDark: Boolean): List<Color> = bucket(onDark)

    /**
     * 整条色（色相 + 不透明度）一起写回去
     *
     * 色板里点格子只改色相、自带的不透明度滑条改 alpha，两件事都从这一个回调进来，
     * 所以这里不再单独保留旧 alpha，否则滑条拖了等于没动。
     */
    fun setColor(onDark: Boolean, index: Int, color: Color) {
        val list = bucket(onDark)
        if (index !in list.indices) return
        list[index] = color
    }

    /** 光晕 / 图标 / 字标 / 圆钮 / 关于页共用的这一份混色 */
    fun blend(onDark: Boolean): List<BlendColorEntry> {
        val list = colors(onDark)
        val mode = modes(onDark)
        return list.mapIndexed { i, c ->
            BlendColorEntry(c, mode[i.coerceAtMost(mode.lastIndex)])
        }
    }

    /** 光晕参数恢复出厂 */
    fun resetGlow() {
        delayMs = Def.DELAY_MS
        durationMs = Def.DURATION_MS
        outerScale = Def.OUTER_SCALE
        thickness = Def.THICKNESS
        easeK = Def.EASE_K
        blurRadius = Def.BLUR_RADIUS
        highlightAlpha = Def.HIGHLIGHT_ALPHA
        fadeStart = Def.FADE_START
    }

    /** 混色恢复出厂 */
    fun resetBlend() {
        darkColors.clear()
        darkColors.addAll(Def.DARK_COLORS)
        lightColors.clear()
        lightColors.addAll(Def.LIGHT_COLORS)
    }

    /**
     * 当前参数导出成 JSON
     *
     * 调试页长按复制，用来把调好的数贴回来改成 [Def] 里的常量。
     * 光晕和混色都在里面：混色是 #AARRGGBB + 混合模式，深底浅底各三条。
     */
    fun toJson(): String {
        fun hex(c: Color) = "#%08X".format(c.toArgb())
        fun group(onDark: Boolean): String {
            val names = modeNames(onDark)
            return colors(onDark).mapIndexed { i, c ->
                "\"${hex(c)} ${names.getOrElse(i) { "" }}\""
            }.joinToString(", ")
        }
        return buildString {
            appendLine("{")
            appendLine("  \"glow\": {")
            appendLine("    \"delayMs\": ${delayMs.roundToInt()},")
            appendLine("    \"durationMs\": ${durationMs.roundToInt()},")
            appendLine("    \"outerScale\": $outerScale,")
            appendLine("    \"thickness\": $thickness,")
            appendLine("    \"easeK\": $easeK,")
            appendLine("    \"blurRadius\": ${blurRadius.roundToInt()},")
            appendLine("    \"highlightAlpha\": $highlightAlpha,")
            appendLine("    \"fadeStart\": $fadeStart")
            appendLine("  },")
            appendLine("  \"blend\": {")
            appendLine("    \"dark\": [ ${group(true)} ],")
            appendLine("    \"light\": [ ${group(false)} ]")
            appendLine("  }")
            append("}")
        }
    }

    /** 出厂值：调试页的默认刻度、单项重置、整体恢复都用这里 */
    object Def {
        // 2026-11-27 调试页定稿：和图标入场几乎同时（100ms）起，半秒铺满，
        // 外径放到 1.5 屏高、环厚 1.2 屏高，整体是一下子涌出来的宽光带
        const val DELAY_MS = 100f
        const val DURATION_MS = 500f
        const val OUTER_SCALE = 1.5f
        const val THICKNESS = 1.2f
        const val EASE_K = 1.2f
        const val BLUR_RADIUS = 90f
        const val HIGHLIGHT_ALPHA = 0.22f
        const val FADE_START = 0.82f

        val DARK_COLORS = listOf(Color(0xe6a1a1a1), Color(0x4de6e6e6), Color(0xff1af500))
        val LIGHT_COLORS = listOf(Color(0xcc4a4a4a), Color(0xff4f4f4f), Color(0xff1af200))
    }
}
