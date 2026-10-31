package com.xkspeed.systemuimount.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 底部弹层（日志一类的长内容用它）
 *
 * 为什么不用 WindowBottomSheet / WindowDialog：
 * 那两个内部是独立的系统 Dialog 窗口，kyant backdrop 采样不到底下的页面，
 * 背景只能盖一层死板的暗色。这里改成当前界面里的浮层，
 * 遮罩直接采样顶栏返回键同一个 Backdrop 做真实模糊（Sonify 的效果）。
 *
 * 版式对齐 SliderInputPreference 里的 WindowDialog：
 * 标题 + 说明 + 内容 + 两个均分按钮。
 * 进出场动画照 WindowDropdownPreference 的 popup：
 * spring(0.82, 362.5) 驱动缩放与透明度 收起走 150ms 的 tween。
 *
 * @param show 是否显示（外部只改这个 退出动画会自己跑完）
 * @param backdrop 采样源 传 null 时遮罩退化成纯暗色
 * @param title 标题
 * @param summary 说明 没有就留空
 * @param content 可滚动的主体
 * @param actions 底部按钮行 调用方一般放两个 weight(1f) 的按钮
 */
@Composable
fun GlassSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    backdrop: Backdrop?,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val progress = remember { Animatable(0f) }
    // show 变 false 后还要把手缩回去 所以渲染与否单独记
    var rendered by remember { mutableStateOf(show) }

    LaunchedEffect(show) {
        if (show) {
            rendered = true
            progress.animateTo(
                1f,
                spring(dampingRatio = 0.82f, stiffness = 362.5f, visibilityThreshold = 0.0001f),
            )
        } else {
            progress.animateTo(0f, tween(durationMillis = 150))
            rendered = false
        }
    }

    if (!rendered) return

    // 返回键先收弹层 不要让页面跟着退
    val backState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = show,
        onBackCompleted = onDismissRequest,
    )

    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    Box(modifier = modifier.fillMaxSize()) {
        // 遮罩：模糊与暗色都跟着 progress 渐入 不会一点开就整屏糊
        val dimModifier = if (backdrop != null) {
            Modifier.drawPlainBackdrop(
                backdrop = backdrop,
                shape = { RectangleShape },
                effects = {
                    blur(18.dp.toPx() * progress.value)
                    vibrancy()
                },
                layerBlock = { alpha = progress.value },
            )
        } else {
            Modifier
        }
        Box(
            Modifier
                .matchParentSize()
                .then(dimModifier)
                .background(Color.Black.copy(alpha = 0.18f * progress.value))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest,
                ),
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer {
                    // 从底部锚点展开 和 dropdown 的 scale 展开同一个手感
                    val p = progress.value
                    val s = 0.15f + 0.85f * p
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    alpha = p
                    translationY = (1f - p) * 48.dp.toPx()
                }
                .shadow(20.dp, sheetShape, clip = false)
                .clip(sheetShape)
                .background(MiuixTheme.colorScheme.surface)
                .clickable(
                    // 卡片自己吃掉点击 否则点空白处会穿透到遮罩把弹层关掉
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(top = 10.dp, bottom = 18.dp),
        ) {
            // 拖动条：和窗口级底部弹层保持一致 让人知道能点外面收起来
            Box(
                Modifier.fillMaxWidth().padding(bottom = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.35f)),
                )
            }

            Text(
                text = title,
                style = MiuixTheme.textStyles.title2,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (!summary.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(14.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                content()
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                content = actions,
            )
        }
    }
}
