package com.xkspeed.HyperMount.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xkspeed.HyperMount.core.AppLog
import com.xkspeed.HyperMount.core.LogLevel
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.xkspeed.HyperMount.ui.util.stableNavigationBarHeight

/**
 * 应用日志页
 *
 * 全局流水由 [AppLog] 落在应用私有目录 这里把它显示出来
 * 用户可以直接复制内容反馈 —— 不用 root、不用连 adb
 *
 * 顶部保留「清空」：日志开详细档之后很容易堆到上限 现场看完就能清掉
 * 崩溃堆栈仍然单独成文件 清日志不会把崩溃现场一起删掉
 */
@Composable
fun AppLogPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }

    fun reload() {
        val text = AppLog.text()
        lines = if (text.isBlank()) listOf("（还没有日志）") else text.lines()
    }

    LaunchedEffect(Unit) { reload() }

    ScrollPage(
        title = "应用日志",
        onBack = onBack,
        bottomPadding = stableNavigationBarHeight() + 24.dp,
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(
                    "文件位置：" + (AppLog.visibleFile()?.absolutePath ?: "未知"),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前等级：${AppLog.level.label} · 共 ${lines.size} 行",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            val text = AppLog.text()
                            if (text.isBlank()) return@Button
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            cm?.setPrimaryClip(
                                android.content.ClipData.newPlainText("app-log", text)
                            )
                            android.widget.Toast.makeText(context, "已复制日志", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    ) { Text("复制全部") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { reload() }) { Text("刷新") }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            AppLog.clear()
                            reload()
                            android.widget.Toast.makeText(context, "已清空", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    ) { Text("清空") }
                }
            }
        }
        itemsIndexed(lines) { _, line ->
            Text(
                line.ifEmpty { " " },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                color = when {
                    line.contains(" E/") || line.contains("Exception") || line.contains("Error") ->
                        MiuixTheme.colorScheme.error
                    // W/D/V 是排查时才看的噪声 压暗一档 主要信息更显眼
                    line.contains(" W/") || line.contains(" D/") || line.contains(" V/") ->
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    else -> MiuixTheme.colorScheme.onSurface
                },
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * 等级下拉只给三档：关闭 / 正常 / 调试
 *
 * E/W/I 只是写入级别（行首标记），不单独当档位暴露；
 * 「调试」已经含全部内容，再留一档「全部」没有意义
 */
val LOG_LEVEL_CHOICES: List<LogLevel> = listOf(LogLevel.OFF, LogLevel.INFO, LogLevel.DEBUG)

/** 等级下拉的文案（设置页与日志页共用一份） */
val LOG_LEVEL_LABELS: List<String> = LOG_LEVEL_CHOICES.map { it.label }

/** 当前等级落在下拉里的下标；老配置残留的 E/W 值按「正常」显示 */
fun logLevelIndex(level: LogLevel): Int =
    LOG_LEVEL_CHOICES.indexOf(level).takeIf { it >= 0 } ?: LOG_LEVEL_CHOICES.indexOf(LogLevel.INFO)
