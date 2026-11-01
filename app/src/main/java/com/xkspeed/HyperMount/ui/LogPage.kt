package com.xkspeed.HyperMount.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 通用日志预览页
 *
 * loader 负责把日志读成文本 autoRefresh 时每 2 秒重新拉一次
 * 不自动刷新就只读一次 适合看已经落盘的调试日志
 *
 * 返回交给 NavDisplay：它自己接了预测性返回 页面再注册 BackHandler
 * 会把手势的进度回调吃掉 返回就变成硬跳转
 */
@Composable
fun LogPage(
    title: String,
    loader: suspend (Int) -> String,
    onBack: () -> Unit,
    autoRefresh: Boolean = false,
) {
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(autoRefresh) {
        loading = lines.isEmpty()
        while (true) {
            val text = loader(400)
            lines = if (text.isBlank()) listOf("（暂无内容）") else text.lines()
            loading = false
            if (!autoRefresh) break
            delay(2000)
        }
    }

    LaunchedEffect(lines.size) {
        if (autoRefresh && lines.size > 1) {
            runCatching { listState.scrollToItem(lines.size - 1) }
        }
    }

    ScrollPage(
        title = title,
        onBack = onBack,
        bottomPadding = WindowInsets.navigationBars.asPaddingValues()
            .calculateBottomPadding() + 24.dp,
    ) {
        itemsIndexed(lines) { _, line ->
            Text(
                line.ifEmpty { " " },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                color = when {
                    line.contains("FAIL") || line.contains("错误") ->
                        MiuixTheme.colorScheme.error
                    line.contains("OK") || line.contains("成功") ->
                        MiuixTheme.colorScheme.primary
                    else -> MiuixTheme.colorScheme.onSurface
                },
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (loading) {
            item {
                Text(
                    "正在读取日志…",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.subtitle,
                )
            }
        }
    }
}
