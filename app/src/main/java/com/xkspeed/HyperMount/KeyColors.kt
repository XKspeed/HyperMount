package com.xkspeed.HyperMount

import androidx.compose.ui.graphics.Color

/** 莫奈取色的种子色表（和示例 App 一致）中文名是信源 英文名只进界面
 *  第一项是"不指定种子 交给系统壁纸" 所以取色用 index-1
 */
val KeyColors: List<SeedColor> = listOf(
    SeedColor("蓝", "Blue", Color(0xFF3482FF)),
    SeedColor("绿", "Green", Color(0xFF36D167)),
    SeedColor("紫", "Purple", Color(0xFF7C4DFF)),
    SeedColor("黄", "Yellow", Color(0xFFFFB21D)),
    SeedColor("橙", "Orange", Color(0xFFFF5722)),
    SeedColor("粉", "Pink", Color(0xFFE91E63)),
    SeedColor("青", "Cyan", Color(0xFF00BCD4)),
)

/** 种子色：中文名（信源）+ 英文名 + 色值 */
data class SeedColor(val zh: String, val en: String, val color: Color) {
    /** 兼容旧的 Pair 用法：.first 中文名 .second 色值 */
    val first: String get() = zh
    val second: Color get() = color
}

/** index 0 = 跟随壁纸（返回 null ThemeController 用系统取色） */
fun keyColorFor(index: Int): Color? =
    if (index <= 0) null else KeyColors.getOrNull(index - 1)?.second
