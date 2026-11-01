package com.xkspeed.HyperMount

import androidx.compose.ui.graphics.Color

/**
 * 莫奈取色的种子色表（和示例 App 一致）
 * 第一项是"不指定种子 交给系统壁纸" 所以取色用 index-1
 */
val KeyColors: List<Pair<String, Color>> = listOf(
    "蓝" to Color(0xFF3482FF),
    "绿" to Color(0xFF36D167),
    "紫" to Color(0xFF7C4DFF),
    "黄" to Color(0xFFFFB21D),
    "橙" to Color(0xFFFF5722),
    "粉" to Color(0xFFE91E63),
    "青" to Color(0xFF00BCD4),
)

/** index 0 = 跟随壁纸（返回 null ThemeController 用系统取色） */
fun keyColorFor(index: Int): Color? =
    if (index <= 0) null else KeyColors.getOrNull(index - 1)?.second
