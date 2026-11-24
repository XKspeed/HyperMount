package com.xkspeed.HyperMount.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xkspeed.HyperMount.core.LogConfig
import com.xkspeed.HyperMount.core.Logcat
import com.xkspeed.HyperMount.i18n.t
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.ui.components.SectionCard
import com.xkspeed.HyperMount.ui.components.SliderInputPreference
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import kotlin.math.roundToInt
import com.xkspeed.HyperMount.ui.util.stableNavigationBarHeight

/**
 * 配置页
 *
 * 主页右上角三点进来。目标包名 / 抓取时长 / 日志大小判定原先挤在主页的弹窗里，
 * 单独成页之后和「界面设置」一个路子：进来改完就退 主页只留测试本身。
 */
@Composable
fun ConfigPage(onBack: () -> Unit) {
    val context = LocalContext.current

    ScrollPage(
        title = t("配置", "Config"),
        subtitle = t("目标包名 · 抓取时长 · 日志大小判定", "Target package · capture time · log size limit"),
        onBack = onBack,
        bottomPadding = stableNavigationBarHeight() + 24.dp,
    ) {
        item { SmallTitle(t("抓取", "Capture")) }
        item {
            SectionCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    TextField(
                        value = LogConfig.pkg,
                        onValueChange = {
                            LogConfig.pkg = it.trim()
                            LogConfig.save(context)
                        },
                        // 空时居中、输入后上浮缩小：和示例里的 With title 同款
                        label = t("目标包名", "Target package"),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    SliderInputPreference(
                        title = t("抓取时长", "Capture time"),
                        summary = t("装完 2 秒后开始算 下一轮生效", "Counts from 2 s after install; applies to the next run"),
                        value = LogConfig.seconds.toFloat(),
                        defaultValue = Logcat.DEFAULT_SECONDS.toFloat(),
                        onValueChange = { LogConfig.seconds = it.roundToInt() },
                        onValueChangeFinished = { LogConfig.save(context) },
                        valueRange = Logcat.MIN_SECONDS.toFloat()..Logcat.MAX_SECONDS.toFloat(),
                        steps = Logcat.MAX_SECONDS - Logcat.MIN_SECONDS - 1,
                        valueText = t("${LogConfig.seconds} 秒", "${LogConfig.seconds} s"),
                        inputUnit = t("秒", "s"),
                        insideMargin = PaddingValues(horizontal = 0.dp, vertical = 14.dp),
                    )
                    SliderInputPreference(
                        title = t("日志大小判定", "Log size limit"),
                        summary = t("crash 日志超过这个大小也算崩", "Crash logs past this size count as a crash too"),
                        value = LogConfig.sizeLimitKb.toFloat(),
                        defaultValue = Logcat.DEFAULT_SIZE_KB.toFloat(),
                        onValueChange = { LogConfig.sizeLimitKb = it.roundToInt() },
                        onValueChangeFinished = { LogConfig.save(context) },
                        valueRange = Logcat.MIN_SIZE_KB.toFloat()..Logcat.MAX_SIZE_KB.toFloat(),
                        steps = Logcat.MAX_SIZE_KB - Logcat.MIN_SIZE_KB - 1,
                        valueText = "${LogConfig.sizeLimitKb} KB",
                        inputUnit = "KB",
                        insideMargin = PaddingValues(horizontal = 0.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}
