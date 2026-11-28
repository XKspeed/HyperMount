package com.xkspeed.HyperMount.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop as GlassLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop as glassLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberGlassBackdrop
import com.xkspeed.HyperMount.ui.component.glass.InteractiveHighlight
import com.xkspeed.HyperMount.ui.component.glass.LiquidGlassPill
import com.xkspeed.HyperMount.ui.component.glass.LiquidTopBarButton
import com.xkspeed.HyperMount.i18n.t
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 标题行 = 条上方 2dp + 指示条 4dp + 按钮 46dp，合计 52dp。
 *
 * 面板顶端到按钮上沿只有 6dp（2dp 空白 + 4dp 条）。
 * 库自带的条固定在手柄区（24dp）正中，距面板顶 10dp，按这个要求就太高了，
 * 所以把库的条隐掉（`hideHandle`）、在这里自己画一条，位置跟着标题行走。
 */
val SHEET_HEADER_HEIGHT = 52.dp

/** 库拖拽手柄区 24dp 整个让给标题行：标题行从面板最顶端开始，条自己画在 2dp 处 */
val SHEET_HEADER_LIFT = 24.dp

private val HEADER_BUTTON = 46.dp

/** 指示条：与库同尺寸（45 × 4，圆角 2），只是位置由我们定 */
private val HANDLE_TOP_GAP = 2.dp
private val HANDLE_HEIGHT = 4.dp
private val HANDLE_WIDTH = 45.dp

/** 图标按按钮比例放大：46dp 圆配 25dp 图标，看着和 42dp 圆配 23dp 一样满 */
private val HEADER_ICON = 25.dp

/**
 * 顶部遮罩：满浓度铺到 40dp，再从 40dp 线性淡到 72dp 全透明。
 *
 * 层高就是 72dp —— 超过标题行 52dp 的那 20dp 向下溢出、压在列表首项上。
 * 面板在 Dialog 窗口里，几种模糊链都画不出东西，这里只用实色渐变。
 */
private const val FADE_TOP_ALPHA = 1f
private const val FADE_START_DP = 40f
private const val FADE_END_DP = 72f
private const val FADE_START_FRACTION = FADE_START_DP / FADE_END_DP

/** 搜索展开 / 收起：整行宽度、放大镜位移、透明度都吃这一条，200ms */
private const val SEARCH_ANIM_MS = 200

/**
 * 放大镜在胶囊里的横向落点：左端那一格（46dp 圆）内的居中偏移。
 * 不是 4dp —— 那样图标左沿只有 4dp、右边到文字还有一大截，看着整个偏左。
 * 胶囊左沿本身会从「搜索格」扩到整行左沿，所以这里是个常量、不用做动画
 */
private val SEARCH_ICON_INSET = (HEADER_BUTTON - HEADER_ICON) / 2

/**
 * 二次函数 ease-in-out：两端速度为 0、中点最快（导数 4t，t = 0.5 时最大）。
 * 默认的 `FastOutSlowInEasing` 是"快出慢进"，位移看着像被拽一下然后拖住，
 * 这里要的是对称的"启动—加速—减速—停"。
 */
private val SearchEase = Easing { t ->
    if (t < 0.5f) 2f * t * t else 1f - 2f * (1f - t) * (1f - t)
}

/**
 * 底部面板的采样源（标题行那几块玻璃读它）
 *
 * 先铺一层面板底色再录内容，不然列表项之间的空档在采样层里是透明的，玻璃会发灰。
 * 底色铺 3 倍并向左上偏移，采样窗口越界时摸到的也是底色而不是透明。
 *
 * 面板内容整块套一层 [glassLayerBackdrop] 才会被录进来。
 */
@Composable
fun rememberSheetBackdrop(): GlassLayerBackdrop {
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberGlassBackdrop(
        onDraw = remember(surfaceColor) {
            {
                drawRect(
                    color = surfaceColor,
                    size = Size(size.width * 3f, size.height * 3f),
                    topLeft = Offset(-size.width, -size.height),
                )
                drawContent()
            }
        },
    )
}

/**
 * 底部面板的玻璃标题行（全项目统一）
 *
 * 顶端压一段线性透明渐变（面板底色 → 全透），列表从底下滚过时渐渐被盖住；
 * 左边一颗玻璃圆钮（记录快照 / 关闭这种主操作）—— 玻璃只在拉满时出现，低档位只剩图标；
 * 右边一格或两格共用**一块**胶囊玻璃，中间直接连起来。
 * 点搜索不跳页：搜索框以右端为锚向左连续拉长，焦点和输入法一起跟过去。
 *
 * 快照面板是「记录 + 搜索 + 退出」；救砖组件选择是「关闭在左 + 只有搜索」，
 * 把 [onClose] 传 null 就少一格，胶囊自己收成一个圆。
 *
 * @param expanded 面板是否已拉到最高档，驱动玻璃底的淡入
 * @param leadingIcon 左上角圆钮的图标
 * @param leadingTintAlpha 图标透明度（正在复制的快照按钮用它压暗）
 * @param onClose 右端那格退出；为 null 时右端只剩搜索
 * @param searchable 当前视图能不能筛；不能筛时右端连搜索格都不摆
 */
@Composable
fun GlassSheetHeader(
    title: String,
    backdrop: Backdrop,
    expanded: Boolean,
    leadingIcon: ImageVector,
    leadingDescription: String,
    onLeading: () -> Unit,
    leadingTintAlpha: Float = 1f,
    onClose: (() -> Unit)? = null,
    searchable: Boolean = false,
    searching: Boolean = false,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onCloseSearch: () -> Unit = {},
    searchHint: String = t("搜索", "Search"),
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(searching) {
        if (searching) {
            // 等展开动画走完再拉输入法：提前弹键盘会把整行顶上去、动画看着就断了
            delay(SEARCH_ANIM_MS.toLong())
            focusRequester.requestFocus()
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }

    // 玻璃底只在拉满时出现：左右两块共用同一个进度，一起淡入淡出
    val glass by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "headerGlass",
    )

    Box(Modifier.fillMaxWidth().height(SHEET_HEADER_HEIGHT)) {
        // 遮罩：位置全部以「标题行顶端 = 0」量，层高就是 72dp。
        //
        // **必须用 requiredHeight**：父 Box 只有 52dp，普通 height(72.dp) 会被夹回 52，
        // 层高、比例、落点全错位。72dp 是真的 72dp，超过 52 的那 20dp 向下溢出、
        // 压在列表首项上。
        //
        // 40dp 往上保持满浓度，40..72dp 线性收到 0
        val fadeColor = MiuixTheme.colorScheme.surface
        val solid = fadeColor.copy(alpha = FADE_TOP_ALPHA)
        val clear = fadeColor.copy(alpha = 0f)
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .requiredHeight(FADE_END_DP.dp)
                .background(
                    Brush.verticalGradient(
                        0f to solid,
                        FADE_START_FRACTION to solid,
                        1f to clear,
                    ),
                ),
        )

        // 自绘的拖拽条：位置贴着手柄下沿，不像库里那样在 24dp 区里居中
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = HANDLE_TOP_GAP)
                .width(HANDLE_WIDTH)
                .height(HANDLE_HEIGHT)
                .clip(RoundedCornerShape(2.dp))
                .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f)),
        )

        BoxWithConstraints(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(HEADER_BUTTON)
                .padding(horizontal = 12.dp),
        ) {
            val fullWidth = maxWidth
            val hasClose = onClose != null
            // 常态胶囊宽 = 现有的格数 × 46：能筛就有一格搜索，有 onClose 就再加一格退出
            val cells = (if (searchable) 1 else 0) + (if (hasClose) 1 else 0)
            val pillWidth = HEADER_BUTTON * cells

            // 展开进度只用来淡入输入框；宽度走 Dp 动画，曲线/时长一致
            val progress by animateFloatAsState(
                targetValue = if (searching) 1f else 0f,
                animationSpec = tween(SEARCH_ANIM_MS, easing = SearchEase),
                label = "searchProgress",
            )
            val barWidth by animateDpAsState(
                targetValue = if (searching) fullWidth else pillWidth,
                animationSpec = tween(SEARCH_ANIM_MS, easing = SearchEase),
                label = "searchWidth",
            )
            // 右端那颗：快照面板一直有退出格，展开后点击换成「退出搜索」；
            // 救砖面板常态只有一格搜索，展开后在这里补一颗同尺寸的叉
            val closeVisible = hasClose || (searchable && searching)

            // 整块胶囊当一颗按钮：按下整条一起压下去、高光跟手，松手按落点分格。
            // 各状态在 lambda 里现读，避免手指按着时面板换状态还走旧分支
            val haptics = LocalHapticFeedback.current
            val density = LocalDensity.current
            val barWidthNow = rememberUpdatedState(barWidth)
            val pillTapState = rememberUpdatedState<(Offset) -> Unit>(
                { pos ->
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    val widthPx = with(density) { barWidthNow.value.toPx() }
                    val cellPx = with(density) { HEADER_BUTTON.toPx() }
                    when {
                        // 展开后整行只剩右端那一格，点哪儿都是退出搜索
                        searching -> onCloseSearch()
                        closeVisible && pos.x >= widthPx - cellPx -> onClose?.invoke()
                        else -> onOpenSearch()
                    }
                },
            )
            val pressScope = rememberCoroutineScope()
            val pillPress = remember(pressScope) {
                InteractiveHighlight(
                    animationScope = pressScope,
                    onTapAt = { pos -> pillTapState.value(pos) },
                )
            }
            val pressInteraction = remember { MutableInteractionSource() }

            Box(Modifier.fillMaxWidth().height(HEADER_BUTTON)) {
                // 左侧主操作 + 标题：搜索时淡出，整行让给胶囊
                AnimatedVisibility(
                    visible = !searching,
                    enter = fadeIn(tween(140)),
                    exit = fadeOut(tween(120)),
                ) {
                    Box(Modifier.fillMaxWidth().height(HEADER_BUTTON)) {
                        LiquidTopBarButton(
                            onClick = onLeading,
                            backdrop = backdrop,
                            icon = rememberVectorPainter(leadingIcon),
                            contentDescription = leadingDescription,
                            modifier = Modifier.align(Alignment.CenterStart),
                            iconSize = HEADER_ICON,
                            // Material 图标自带基线偏下，实机上看着不居中，往上 1dp 找平
                            iconOffset = DpOffset(0.dp, (-1).dp),
                            buttonHeight = HEADER_BUTTON,
                            backdropAlpha = { glass },
                            shadowAlpha = { glass },
                            edgeLightAlpha = { glass },
                            iconTint = MiuixTheme.colorScheme.onSurface.copy(alpha = leadingTintAlpha),
                            // 和主界面顶栏那颗同一套：按下放大 + 高光、跟手微倾、松手回弹。
                            // 手势由按钮自己消费，按住它拖不会再带着整块面板走
                            draggable = true,
                        )
                        Text(
                            title,
                            modifier = Modifier
                                .align(Alignment.Center)
                                // 两边各让出一颗按钮 + 边距，标题再长也不会压到按钮上
                                .padding(horizontal = 58.dp),
                            color = MiuixTheme.colorScheme.onSurface,
                            style = MiuixTheme.textStyles.title4,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // 一整块胶囊：常态「搜索格 + 退出格」，展开时只是变宽。
                // 退出格钉在右端从头到尾不动，中间那段就是这块背景自己拉长
                if (searchable || closeVisible) {
                    LiquidGlassPill(
                        backdrop = backdrop,
                        height = HEADER_BUTTON,
                        glassAlpha = { glass },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(barWidth)
                            // 按压变换作用在整块胶囊上（玻璃 / 图标 / 输入框同一图层），
                            // 口径与左侧圆钮一致：+6dp 当量、朝手指方向微倾 5%
                            .graphicsLayer {
                                val p = pillPress.pressProgress
                                if (p > 0f) {
                                    val scale = 1f + 6f.dp.toPx() / HEADER_BUTTON.toPx() * p
                                    scaleX = scale
                                    scaleY = scale
                                    val press = pillPress.offset
                                    translationX = size.minDimension * 0.05f * press.x / size.maxDimension
                                    translationY = size.minDimension * 0.05f * press.y / size.maxDimension
                                }
                            },
                    ) {
                        // 高光单独垫在最底层：它没有指针输入，不会阻断任何命中，
                        // 又能保证按下去的光在图标和玻璃底下（不会把图标洗白）
                        Box(
                            Modifier
                                .matchParentSize()
                                .then(pillPress.modifier),
                        )

                        // 放大镜画在胶囊里面：胶囊左沿一路从「搜索格」扩到整行左沿，
                        // 它相对左沿的距离恒定 —— 看着就是图标平滑滑过去，而且
                        // 按压缩放时跟着玻璃一起动（放外面的话玻璃放大、图标不动）
                        if (searchable) {
                            Icon(
                                imageVector = MiuixIcons.Search,
                                contentDescription = if (searching) null else t("搜索", "Search"),
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .offset(x = SEARCH_ICON_INSET)
                                    .size(HEADER_ICON),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }

                        if (closeVisible) {
                            Row(
                                Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 只画图标：动作已经交给整块背景那条手势按落点分了
                                HeaderIcon(
                                    icon = MiuixIcons.Close,
                                    description = if (searching) {
                                        t("退出搜索", "Exit search")
                                    } else {
                                        t("退出", "Close")
                                    },
                                )
                            }
                        }
                        // 输入框宽度固定为「整行 - 退出格」，常态靠 alpha 藏起来、
                        // 被胶囊裁掉；展开时随进度淡入。布局全程不动，动的只有胶囊宽度
                        //
                        // 外面这层 Box 是必需的：输入框按整行宽排版（requiredWidth），
                        // 命中区域也是那么宽 —— 不裁的话它会盖住右端那颗退出键，
                        // 点关闭没反应就是这个（clip 只裁绘制，不裁命中）
                        if (searchable) {
                            Box(
                                Modifier
                                    .align(Alignment.CenterStart)
                                    .width(fullWidth - if (closeVisible) HEADER_BUTTON else 0.dp)
                                    .clipToBounds()
                                    .graphicsLayer { alpha = progress },
                            ) {
                                SearchField(
                                    width = fullWidth - if (closeVisible) HEADER_BUTTON else 0.dp,
                                    query = query,
                                    onQueryChange = onQueryChange,
                                    // 常态虽然 alpha 0，但 clip 只管绘制不管命中，
                                    // 不禁用的话点标题左边那一截会莫名其妙聚焦到输入框
                                    enabled = searching,
                                    focusRequester = focusRequester,
                                    hint = searchHint,
                                )
                            }
                        }

                        // 整块背景就是按钮：手势层必须是 content 的**最后**一个子项。
                        // 命中测试从最上层子项往下来，放在图标/输入框下面会被它们
                        // 拦住（输入框节点即使 enabled = false 也在命中链上），
                        // 表现就是整块点了没反应。
                        //
                        // 常态铺满整条，展开后收窄到右端那一格 —— 展开时整行是输入框，
                        // 铺满的话输入框就点不到了。
                        // clickable 只为消费 down：不消费的话标题行的 draggable 会把
                        // 这里的拖动抢走，按住胶囊就变成拖整块面板了
                        Box(
                            Modifier
                                .align(Alignment.CenterEnd)
                                .width(if (searching) HEADER_BUTTON else barWidth)
                                .height(HEADER_BUTTON)
                                .clickable(
                                    interactionSource = pressInteraction,
                                    indication = null,
                                    role = Role.Button,
                                    onClick = {},
                                )
                                .then(pillPress.gestureModifier),
                        )
                    }
                }
            }
        }
    }
}

/** 标题行右侧的一格：只画图标，底色与点击都由外面那条胶囊统一给 */
@Composable
private fun HeaderIcon(
    icon: ImageVector,
    description: String,
) {
    Box(
        Modifier.size(HEADER_BUTTON),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(HEADER_ICON),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 展开后的整行输入框
 *
 * 左边空出放大镜的位置（它在外面那层、跟着动画平移进来），右端交给常驻的退出格，
 * 这里不再自带小叉 —— 退出格的位置从头到尾不动，只换点击行为
 */
@Composable
private fun SearchField(
    width: Dp,
    query: String,
    onQueryChange: (String) -> Unit,
    enabled: Boolean,
    focusRequester: FocusRequester,
    hint: String,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        enabled = enabled,
        modifier = modifier
            // requiredWidth：忽略胶囊当前给的窄窗口，按整行宽排版，超出部分由胶囊裁掉
            .requiredWidth(width)
            .height(HEADER_BUTTON)
            // 文字起点接在放大镜右沿后面 10dp：放大镜落点是格内居中，
            // 这里跟着一起右移，两者的间距才和常态下一致
            .padding(start = SEARCH_ICON_INSET + HEADER_ICON + 10.dp, end = 10.dp)
            .focusRequester(focusRequester),
        singleLine = true,
        textStyle = MiuixTheme.textStyles.main.copy(color = MiuixTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        hint,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.main,
                    )
                }
                inner()
            }
        },
    )
}
