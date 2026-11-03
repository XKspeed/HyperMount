package com.xkspeed.HyperMount.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xkspeed.HyperMount.core.CacheConfig
import com.xkspeed.HyperMount.core.Logcat
import com.xkspeed.HyperMount.core.Module
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.ui.components.SectionCard
import com.xkspeed.HyperMount.ui.components.SliderInputPreference
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File

private const val MB = 1024L * 1024L

/**
 * 缓存管理
 *
 * 只管路 cache/stage 里那份待测 apk 副本：调上限、勾选删除。
 * 上限本身就是修剪阈值，改小会立刻按新值收一次。
 */
@Composable
fun CachePage(onBack: () -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var total by remember { mutableStateOf(0L) }
    var marked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var limitMb by remember {
        mutableFloatStateOf((CacheConfig.limit / MB).toFloat().coerceIn(50f, 2000f))
    }
    var askDelete by remember { mutableStateOf(false) }

    fun reload() {
        val list = Logcat.stageFiles()
        files = list
        total = list.sumOf { it.length() }
        // 文件没了就把勾选一起收掉 免得删一个不存在的东西
        marked = marked.filter { p -> list.any { it.absolutePath == p } }.toSet()
    }

    LaunchedEffect(Unit) { reload() }

    val limitBytes = (limitMb.toLong() * MB).coerceIn(CacheConfig.MIN, CacheConfig.MAX)
    // 已用可能比上限还大 进度条画满为止
    val ratio = if (limitBytes <= 0L) 0f else (total.toFloat() / limitBytes).coerceIn(0f, 1f)
    val picked = files.filter { it.absolutePath in marked }
    val pickedSize = picked.sumOf { it.length() }

    ScrollPage(
        title = "缓存",
        onBack = onBack,
        // 底部固定条不吃列表高度：末尾多留一条 滚到底时最后一项停在它上方
        bottomPadding = WindowInsets.navigationBars.asPaddingValues()
            .calculateBottomPadding() + 112.dp,
        // 删除条和底栏一样钉在页面底部 不跟着列表滚：
        // 列表滚动时它会从卡片底下穿过去 所以先垫一层从透明到页面底色的渐变
        overlay = {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, MiuixTheme.colorScheme.surface)
                        )
                    )
                    .padding(top = 20.dp)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    // 半透明红叠在内容上会透字 这里直接混成不透明的淡红
                    colors = CardDefaults.defaultColors(
                        color = lerp(
                            MiuixTheme.colorScheme.surface,
                            MiuixTheme.colorScheme.error,
                            0.12f,
                        ),
                    ),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = picked.isNotEmpty()) { askDelete = true }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (picked.isEmpty()) "删除" else "删除 ${picked.size} 项",
                            color = if (picked.isEmpty()) {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            } else {
                                MiuixTheme.colorScheme.error
                            },
                            style = MiuixTheme.textStyles.main,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        },
    ) {
        item { SmallTitle("占用") }
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    // 全长 = 上限，填满的部分 = 当前占用
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MiuixTheme.colorScheme.secondaryVariant)
                    ) {
                        if (ratio > 0f) {
                            Box(
                                Modifier
                                    .fillMaxWidth(ratio)
                                    .fillMaxHeight()
                                    .background(MiuixTheme.colorScheme.primary)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            "已用 " + Module.fmtSize(total),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.subtitle,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "上限 " + Module.fmtSize(limitBytes),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.subtitle,
                        )
                    }
                    SliderInputPreference(
                        title = "缓存上限",
                        value = limitMb,
                        onValueChange = { limitMb = it },
                        onValueChangeFinished = {
                            CacheConfig.set(context, limitMb.toLong() * MB)
                            Logcat.pruneStage()
                            reload()
                        },
                        valueRange = 50f..2000f,
                        steps = 38,
                        valueText = Module.fmtSize(limitMb.toLong() * MB),
                        inputUnit = "MB",
                        insideMargin = PaddingValues(horizontal = 0.dp, vertical = 10.dp),
                    )
                }
            }
        }

        item { SmallTitle("文件") }
        item {
            SectionCard {
                if (files.isEmpty()) {
                    Text(
                        "缓存目录是空的",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.subtitle,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                } else {
                    files.forEach { f ->
                        val checked = f.absolutePath in marked
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    marked = if (checked) {
                                        marked - f.absolutePath
                                    } else {
                                        marked + f.absolutePath
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    f.name,
                                    style = MiuixTheme.textStyles.main,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    Module.fmtSize(f.length()),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.subtitle,
                                )
                            }
                            Checkbox(
                                state = if (checked) ToggleableState.On else ToggleableState.Off,
                                onClick = {
                                    marked = if (checked) {
                                        marked - f.absolutePath
                                    } else {
                                        marked + f.absolutePath
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    WindowDialog(
        show = askDelete,
        title = "删除缓存",
        summary = "释放 ${Module.fmtSize(pickedSize)}",
        maxWidth = 560.dp,
        onDismissRequest = { askDelete = false },
    ) {
        Text(
            "将删除 ${picked.size} 个 apk 缓存 下次测试会重新复制一份",
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.subtitle,
        )
        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(
                text = "取消",
                onClick = { askDelete = false },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = "删除",
                onClick = {
                    Logcat.deleteStage(picked)
                    marked = emptySet()
                    askDelete = false
                    reload()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
