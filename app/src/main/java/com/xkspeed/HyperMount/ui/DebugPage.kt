package com.xkspeed.HyperMount.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xkspeed.HyperMount.core.AppLog
import com.xkspeed.HyperMount.core.LogLevel
import com.xkspeed.HyperMount.i18n.t
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.ui.components.LiquidSelectPreference
import com.xkspeed.HyperMount.ui.components.SectionCard
import com.xkspeed.HyperMount.ui.components.rememberLiquidSelectState
import com.xkspeed.HyperMount.ui.util.stableNavigationBarHeight
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 开发者选项：正式包也保留这个入口。
 *
 * 三样东西：
 * - 应用日志等级 / 应用日志内容（从设置页整组挪过来）
 * - 进入 OOBE：不用清数据、不用等下次冷启动，直接放一遍首屏
 *
 * 底栏图标调参已经撤掉，数值定死在 NavIconTuning 的 defaults 里；
 * 面板顶栏遮罩也定稿（1 / 40dp / 72dp），没有滑条。
 */
@Composable
fun DebugPage(
    onBack: () -> Unit,
    onOpenOobe: () -> Unit,
    onOpenAppLog: () -> Unit,
) {
    val context = LocalContext.current
    val select = rememberLiquidSelectState()

    ScrollPage(
        title = t("开发者选项", "Developer options"),
        onBack = onBack,
        bottomPadding = stableNavigationBarHeight() + 24.dp,
    ) {
        item { SmallTitle(t("应用日志", "App log")) }
        item {
            SectionCard {
                // 这两行不带副标题：下拉右侧已经写着当前档位 文件行点进去就是内容
                LiquidSelectPreference(
                    title = t("应用日志等级", "App log level"),
                    items = LOG_LEVEL_LABELS(),
                    selectedIndex = logLevelIndex(AppLog.level),
                    onSelectedIndexChange = {
                        AppLog.setLevel(context, LOG_LEVEL_CHOICES[it])
                    },
                    state = select,
                )
                // 等级为「关闭」时不写日志 内容那行跟着收起
                AnimatedVisibility(
                    visible = AppLog.level != LogLevel.OFF,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column {
                        ArrowPreference(
                            title = t("应用日志内容", "App log content"),
                            onClick = onOpenAppLog,
                        )
                    }
                }
            }
        }

        item { SmallTitle(t("OOBE", "OOBE")) }
        item {
            SectionCard {
                // 入口这一行不带副标题
                ArrowPreference(
                    title = t("进入 OOBE", "Open OOBE"),
                    onClick = onOpenOobe,
                )
            }
        }
    }
}
