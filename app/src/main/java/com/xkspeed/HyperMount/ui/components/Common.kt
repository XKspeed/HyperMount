package com.xkspeed.HyperMount.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 分组卡片
 *
 * 里面直接放 Miuix 的 Preference 组件：它们自带 insideMargin
 * 这里再补水平内边距会变成双重缩进
 * 自定义内容（InfoRow 之类）自己负责留白
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        content()
    }
}

/** 通用列表行（文件选择器用）：标题 + 副标题 + 尾部 */
@Composable
fun ListRow(
    title: String,
    subtitle: String = "",
    trailing: String = "",
    onClick: (() -> Unit)? = null,
) {
    val modifier = Modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 13.dp)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.main, maxLines = 1)
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.subtitle,
                    maxLines = 1,
                )
            }
        }
        if (trailing.isNotEmpty()) {
            Spacer(Modifier.width(12.dp))
            Text(
                trailing,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                style = MiuixTheme.textStyles.subtitle,
            )
        }
    }
}

/** 信息行：左标签右值 */
@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)) {
        Text(
            label,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.subtitle,
            modifier = Modifier.width(84.dp),
        )
        Text(value, style = MiuixTheme.textStyles.main)
    }
}

/**
 * 步进器：标题 + 当前值 + 减/加
 * Miuix 没有现成的 Stepper 这里用两个圆钮拼 视觉和 Preference 行一致
 */
@Composable
fun StepperRow(
    title: String,
    value: Int,
    unit: String,
    range: IntRange,
    step: Int = 1,
    summary: String? = null,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.main)
            if (!summary.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.subtitle,
                )
            }
        }
        StepButton("−", enabled = value - step >= range.first) { onChange(value - step) }
        Box(Modifier.width(62.dp), contentAlignment = Alignment.Center) {
            Text("$value $unit", style = MiuixTheme.textStyles.main)
        }
        StepButton("+", enabled = value + step <= range.last) { onChange(value + step) }
    }
}

@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MiuixTheme.textStyles.main,
            color = if (enabled) MiuixTheme.colorScheme.onSurface
            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 状态圆点 */
@Composable
fun StatusDot(color: androidx.compose.ui.graphics.Color, size: Int = 9) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/** Miuix 风格对话框 */
@Composable
fun MiuixDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
            shape = RoundedCornerShape(28.dp),
            color = MiuixTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                Text(title, style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                content()
            }
        }
    }
}

@Composable
fun DialogActions(
    confirmText: String = "确定",
    onConfirm: () -> Unit,
    cancelText: String = "取消",
    onCancel: () -> Unit,
) {
    Spacer(Modifier.height(18.dp))
    HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onCancel) { Text(cancelText) }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onConfirm) { Text(confirmText) }
    }
}

/** 顶栏返回按钮：用 Miuix 自带的返回箭头 RTL 下会自动镜像 */
@Composable
fun BackIcon(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(modifier = modifier, onClick = onClick) {
        Icon(
            imageVector = MiuixIcons.Back,
            contentDescription = "返回",
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}
