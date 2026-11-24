package com.xkspeed.HyperMount.i18n

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 全局语言状态
 * 0 = 跟随系统 1 = 简体中文 2 = English
 *
 * 文案不进资源表：中文是唯一信源，英文内嵌在各调用处。取文案统一走 [t]。
 * isEn 背后是快照状态：组合期间读到它，语言一换整棵组合树自动重组，
 * 不用重建 Activity，切换立即生效。
 */
object AppLang {
    var pref by mutableIntStateOf(LANG_SYSTEM)
        private set

    const val LANG_SYSTEM = 0
    const val LANG_ZH = 1
    const val LANG_EN = 2

    private const val PREFS = "lang_config"
    private const val KEY_PREF = "pref"

    /** 启动时解析一次的系统语言：中文系 → false 其他一律 → true */
    private var systemEn = false

    /** MainActivity.onCreate 里、setContent 之前调用：恢复上次的选择并记住系统语言 */
    fun load(context: Context) {
        val locale = context.resources.configuration.locales.get(0)
        systemEn = locale?.language != "zh"
        pref = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_PREF, LANG_SYSTEM)
    }

    /** 设置页切换语言时调用：落盘并立即生效 */
    fun setPref(value: Int, context: Context) {
        if (pref == value) return
        pref = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PREF, value).apply()
    }

    /** 当前生效的是不是英文（跟随系统时看系统语言） */
    val isEn: Boolean
        get() = when (pref) {
            LANG_EN -> true
            LANG_ZH -> false
            else -> systemEn
        }
}

/** 取文案：调用处成对给中英两份 中文是原文 英文是翻译
 *  英文漏了就是空串 那种情况回中文 不让界面出现空白 */
fun t(zh: String, en: String): String = if (AppLang.isEn && en.isNotBlank()) en else zh
