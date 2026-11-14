package com.xkspeed.HyperMount.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import com.xkspeed.HyperMount.ui.Locate
import com.xkspeed.HyperMount.ui.LocateTarget
import com.xkspeed.HyperMount.ui.UiConfigState
import com.xkspeed.HyperMount.ui.component.glass.bottomBarTopOffset
import com.xkspeed.HyperMount.ui.component.glass.frostedCapsule
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
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
 *
 * 定位高亮优先加在具体那一行上（[locateHighlight]）；整卡描边只留给「卡里就一行」的场合。
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    /** 大于 0 时闪一次主色描边：卡里只有一行内容时用它，2 秒渐隐 */
    highlight: Int = 0,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glow = remember { Animatable(0f) }
    LaunchedEffect(highlight) {
        if (highlight > 0) {
            glow.snapTo(1f)
            glow.animateTo(0f, tween(durationMillis = 2000, easing = LinearEasing))
        }
    }
    val accent = MiuixTheme.colorScheme.primary
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .drawWithContent {
                drawContent()
                if (glow.value > 0f) {
                    val stroke = 2.dp.toPx()
                    drawRoundRect(
                        color = accent.copy(alpha = glow.value),
                        topLeft = Offset(stroke / 2f, stroke / 2f),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(16.dp.toPx() - stroke / 2f),
                        style = Stroke(width = stroke),
                    )
                }
            },
    ) {
        content()
    }
}

/**
 * 定位高亮：给**某一行**铺一层主色底（2 秒渐隐）
 *
 * 拦截提示说「先选待测界面」时，只亮那一行，不把整张卡圈起来——
 * 一张卡里往往并排放着救砖 / 待测好几行，整卡高亮看不出到底该点哪个。
 * 只在 [Locate.flashTarget] 指向自己时才闪；同一个目标再点一次（[Locate.flash] 变化）会重放。
 */
@Composable
fun Modifier.locateHighlight(target: LocateTarget): Modifier {
    val glow = remember { Animatable(0f) }
    val active = Locate.flashTarget == target
    LaunchedEffect(Locate.flash, active) {
        if (!active) {
            glow.snapTo(0f)
            return@LaunchedEffect
        }
        glow.snapTo(1f)
        glow.animateTo(0f, tween(durationMillis = 2000, easing = LinearEasing))
        // 播完就摘掉目标：页面重组（切页回来、列表重绘）时不再重放
        Locate.clear(target)
    }
    val accent = MiuixTheme.colorScheme.primary
    return drawBehind {
        val alpha = glow.value
        if (alpha > 0.001f) {
            drawRoundRect(
                color = accent.copy(alpha = 0.16f * alpha),
                cornerRadius = CornerRadius(14.dp.toPx()),
            )
        }
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

/**
 * 页面底部的常驻操作条：一条悬浮胶囊
 *
 * 只有一张卡，按状态换文案与点击行为：空闲是「开始测试」，真正跑起来之后同一条卡变成「停止」。
 * 启动中的过渡态显示「启动中…」并禁用，避免连点两次。
 * 「停止」只是文字换成 error 色，卡片材质与普通卡片完全一样。
 *
 * 浮动在列表之上、底栏之上，不跟着列表滚：
 * - 位置相对**实测的底栏顶边**（[bottomBarTopOffset]）：标准条贴底、悬浮条还多一层 inset，
 *   写死 64/70 一换样式就错位
 * - 材质跟当前底栏走：标准底栏用实心 surfaceContainer（就是应用里其它卡片的颜色，
 *   背景是 surface，压上去才看得出是张卡）；液态胶囊底栏走 [frostedCapsule] 与底栏同一块料
 * - 拿不到 backdrop（安全模式 / 页面没给）时退回实心 surface 卡片
 *
 * 高度变化（按钮文案换行之类）不影响布局：列表的底部间距由调用方单独给。
 * Snackbar 定位要用 [BottomActionCardHeight] / [BottomActionBarGap] 算，别另写数字。
 */
@Composable
fun BoxScope.BottomActions(
    backdrop: Backdrop?,
    running: Boolean,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val glass = backdrop != null && !UiConfigState.safeMode

    Row(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            // 宽度与底栏一致（93%） 左右边缘对齐
            .fillMaxWidth(0.93f)
            // 底栏顶边已含系统导航条，这里只需再让开底栏本身
            .padding(bottom = bottomBarTopOffset() + BottomActionBarGap),
    ) {
        ActionCard(
            backdrop = if (glass) backdrop else null,
            label = when {
                busy -> "启动中…"
                running -> "停止"
                else -> "开始测试"
            },
            enabled = !busy,
            tone = if (running) ActionTone.Danger else ActionTone.Accent,
            onClick = if (running) onStop else onStart,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 操作卡高度：调用方（Snackbar 定位）也算这份值，别各写各的 */
val BottomActionCardHeight = 52.dp
/** 卡片与底栏之间留的空 */
val BottomActionBarGap = 10.dp
private val ActionCardShape = Capsule()

/** 操作卡文字配色：主操作走主题色，停止只把文字换成红色，卡片材质不变 */
private enum class ActionTone { Accent, Danger }

/**
 * 单张操作卡：材质跟着当前底栏走，看着才是同一套
 *
 * - 液态胶囊底栏：走 [frostedCapsule]，和底栏一样有黑色环绕描边 + 白提亮高光
 * - 标准底栏：底栏是一条平面磨砂条，卡片就用应用里其它卡片的 surfaceContainer 实心
 *   （页面底层是 surface，这里实心提一层才看得出是张卡；不能跟着底栏做半透明，
 *   深色下会和纯黑背景糊成一片，只剩文字悬空）
 */
@Composable
private fun ActionCard(
    backdrop: Backdrop?,
    label: String,
    enabled: Boolean,
    tone: ActionTone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = when (tone) {
        ActionTone.Accent -> MiuixTheme.colorScheme.primary
        ActionTone.Danger -> MiuixTheme.colorScheme.error
    }
    val solid = MiuixTheme.colorScheme.surfaceContainer
    Box(
        modifier = modifier
            .height(BottomActionCardHeight)
            .then(
                when {
                    backdrop == null -> Modifier.background(solid, ActionCardShape)
                    // 液态玻璃底栏是磨砂胶囊：卡片跟着用同一块料
                    UiConfigState.iosBottomBar -> Modifier.frostedCapsule(backdrop, ActionCardShape)
                    // 标准底栏：实心卡片，不带描边与投影
                    else -> Modifier.background(solid, ActionCardShape)
                }
            ),
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 26.dp,
            colors = ButtonDefaults.buttonColors(
                color = Color.Transparent,
                disabledColor = Color.Transparent,
                contentColor = content,
                disabledContentColor = content.copy(alpha = 0.38f),
            ),
            insideMargin = PaddingValues(horizontal = 8.dp),
        ) {
            Text(label)
        }
    }
}
