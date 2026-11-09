package com.xkspeed.HyperMount.ui.component.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInCirc
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import com.xkspeed.HyperMount.ui.util.stableStatusBarHeight
import com.xkspeed.HyperMount.ui.util.stableStatusBarsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.xkspeed.HyperMount.R
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.utils.overScrollVertical
import com.xkspeed.HyperMount.BuildConfig
import com.xkspeed.HyperMount.core.BuildVariant
import com.xkspeed.HyperMount.ui.components.UnofficialBanner

/** 顶栏（小标题那一条）的高度 与列表顶部留白同源 */
private val BarHeight = 54.dp

/** 大标题字号 Sonify 用 35sp/40sp 行高 */
private val LargeTitleSize = 35.sp

/**
 * 页面骨架：大标题 + 玻璃顶栏 + 列表
 *
 * 和 Miuix 的 `Scaffold + TopAppBar` 的根本区别：**大标题是列表的第一项**
 * 不是顶栏的一部分 于是它跟着列表一起滚出去 顶栏那条永远只在最上面
 * 不存在"大标题溢出顶栏、和小标题叠在一起"的问题
 *
 * 折叠分三段（见 [calculateTitleCollapse]）：
 * 1. 大标题还没碰到顶栏 → 完全显示 顶栏透明
 * 2. 大标题开始没入 → 顶栏出现渐进模糊 大标题淡出
 * 3. 大标题完全没入 → 顶栏实心模糊 小标题淡入
 *
 * @param onBack 传 null 表示这是一级页（底栏 tab） 不画返回键
 * @param backIcon 返回键图标 默认是 Sonify 同款的实心箭头（res/drawable/ic_back.xml）
 *   不再用 Miuix 的细线箭头 传 null 或省略都走这个默认
 * @param smallTitle 折叠后顶栏小标题的文案 默认同 [title]；文件选择器用它显示当前路径
 * @param onTitleClick 顶栏小标题的点击回调（文件选择器：返回上一级目录）
 * @param onTitleLongClick 顶栏小标题的长按回调（文件选择器：输入路径跳转）
 */
@Composable
fun ScrollPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    /** 返回键图标 传 null 用 Sonify 同款实心箭头 */
    backIcon: Painter? = null,
    /** 右上角动作键：与左上角返回键同款玻璃圆钮 传 null 不画 */
    onTopAction: (() -> Unit)? = null,
    /** 右上角动作键的图标 传 null 用关闭图标 */
    topActionIcon: Painter? = null,
    /** 右上角动作键的无障碍描述 */
    topActionDescription: String = "关闭",
    /** 右上角动作键的图标边长 与返回键一样走 42dp 圆钮 但图标可以单独给尺寸 */
    topActionIconSize: Dp = 24.dp,
    /** 右上角动作键图标的位移 正数向右 / 向下 用来纠图标本身的重心 */
    topActionIconOffset: DpOffset = DpOffset.Zero,
    smallTitle: String? = null,
    /** 版本号右侧的附加内容（如「有新版本可用」按钮） 跟着大标题一起滚动淡出 */
    subtitleExtra: @Composable RowScope.() -> Unit = {},
    onTitleClick: (() -> Unit)? = null,
    onTitleLongClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    bottomPadding: Dp = 24.dp,
    backgroundContent: @Composable BoxScope.() -> Unit = {},
    /** 页面级浮层（下拉弹层等）：画在列表与顶栏之上 拿到的是同一份 backdrop 才能做真模糊 */
    overlay: @Composable BoxScope.(Backdrop) -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val pageColor = MiuixTheme.colorScheme.surface

    // isDebuggable == true 代表「不是正式证书签的」——改包重签必然落到这一支
    // debug 构建一律放行：自测包随便重签都不该出现红条
    val unofficial = remember { !BuildConfig.DEBUG && BuildVariant.isDebuggable }
    // 采样层必须"先铺页面底色、再录列表内容"（对齐 Sonify 的 rememberTitleBackdrop）：
    // 否则列表项之间的空档在采样层里是透明的 自适应亮度会把它当成暗色
    // 玻璃的亮度/对比/图标色跟着一起跑偏（用户看到的"返回键玻璃颜色不对"）
    // 底色铺 3 倍并向左上偏移 是为了让采样窗口越界时摸到的也是底色而不是透明
    val backdrop = rememberLayerBackdrop(
        onDraw = remember(pageColor) {
            {
                drawRect(
                    color = pageColor,
                    size = Size(size.width * 3f, size.height * 3f),
                    topLeft = Offset(-size.width, -size.height),
                )
                drawContent()
            }
        }
    )
    val collapse = rememberTitleCollapse(listState)
    // 状态栏高度取「忽略可见性」那一份：切多任务时系统栏隐掉 这里不能跟着变 0
    val statusBarHeight = stableStatusBarHeight()

    Box(
        modifier
            .fillMaxSize()
            // 二级页没有这层就透出一级页：NavDisplay 转场时下层还在画面里
            .background(MiuixTheme.colorScheme.surface)
    ) {
        // 背景层（如关于页的流光）与列表一起录进 backdrop：
        // 顶栏玻璃（drawPlainBackdrop）采样到的就是「流光 + 列表」 折叠后透出流光而非黑底
        Box(Modifier.matchParentSize().layerBackdrop(backdrop)) {
            Box(Modifier.matchParentSize()) { backgroundContent() }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical(),
                contentPadding = PaddingValues(top = BarHeight),
            ) {
            item(key = "large-title") {
                Column {
                    Spacer(Modifier.height(statusBarHeight))
                    LargeTitle(title, subtitle, collapse.alpha, subtitleExtra)
                }
            }
            // 非官方签名：贴在内容最顶端，跟着列表一起滚（大标题之下、第一张卡片之上）
            if (unofficial) {
                item(key = "unofficial") { UnofficialBanner() }
            }
            content()
            item(key = "bottom-space") {
                // 底部留白由调用方算全（底栏页要含底栏高度 二级页要含导航栏 inset）
                Spacer(Modifier.height(bottomPadding))
            }
        }
        }

        GlassTopBar(
            title = title,
            smallTitleText = smallTitle ?: title,
            onTitleClick = onTitleClick,
            onTitleLongClick = onTitleLongClick,
            onBack = onBack,
            backIcon = backIcon,
            onTopAction = onTopAction,
            topActionIcon = topActionIcon,
            topActionDescription = topActionDescription,
            topActionIconSize = topActionIconSize,
            topActionIconOffset = topActionIconOffset,
            actions = actions,
            backdrop = backdrop,
            barVisible = collapse.bar,
            smallTitleVisible = collapse.small,
        )

        overlay(backdrop)
    }
}

/** 大标题：35sp 粗体 随滚动淡出 */
@Composable
private fun LargeTitle(
    title: String,
    subtitle: String?,
    alpha: () -> Float,
    subtitleExtra: @Composable RowScope.() -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 12.dp)
            // 透明度在绘制期读 滚动时不重组
            .graphicsLayer { this.alpha = alpha() },
    ) {
        Text(
            text = title,
            fontSize = LargeTitleSize,
            fontWeight = FontWeight.Bold,
            lineHeight = 40.sp,
            color = MiuixTheme.colorScheme.onBackground,
        )
        if (subtitle != null) {
            Row(
                modifier = Modifier.padding(horizontal = 2.5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                subtitleExtra()
            }
        }
    }
}

/**
 * 玻璃顶栏
 *
 * 背景不是"整块等半径模糊 + 渐变遮罩" 而是 AGSL 逐像素半径变化的真渐进模糊
 * （顶部 12dp 收到 0 并向栏下多延伸 40dp 作为淡出尾巴） 所以大标题是"虚化着沉进"
 * 顶栏 而不是被一条硬边切断
 */
@Composable
private fun GlassTopBar(
    title: String,
    smallTitleText: String,
    onTitleClick: (() -> Unit)?,
    onTitleLongClick: (() -> Unit)?,
    onBack: (() -> Unit)?,
    backIcon: Painter?,
    onTopAction: (() -> Unit)?,
    topActionIcon: Painter?,
    topActionDescription: String,
    topActionIconSize: Dp,
    topActionIconOffset: DpOffset,
    actions: @Composable RowScope.() -> Unit,
    backdrop: Backdrop,
    barVisible: () -> Boolean,
    smallTitleVisible: () -> Boolean,
) {
    val pageColor = MiuixTheme.colorScheme.surface

    // 玻璃材质的透明度跟随顶栏出现与否：顶栏还没出现时（顶栏透明）返回键是纯图标
    // 顶栏出现后才变成带圆玻璃底的液态玻璃钮 与原版同一时机
    // 用 snapshotFlow 持续追踪：barVisible 是绘制期读取的 lambda
    // 直接 LaunchedEffect(barVisible()) 只会拿到组合那一刻的值
    val barGlassAlpha = remember { Animatable(0f) }
    LaunchedEffect(barVisible) {
        snapshotFlow { barVisible() }
            .distinctUntilChanged()
            .collect { on ->
                barGlassAlpha.animateTo(
                    if (on) 1f else 0f,
                    tween(150, easing = EaseOut),
                )
            }
    }

    Box(Modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = barVisible(),
            modifier = Modifier.matchParentSize(),
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(120)),
        ) {
            // 顶栏玻璃：对齐 Sonify TitleBar（默认开启「工具栏液态玻璃」） 用 Sonify 同款
            // titleBarProgressiveBlur —— 真·渐进模糊（顶部 12dp 收到 0 并向栏下多延伸 40dp
            // 作淡出尾巴） 大标题是「虚化着沉进」顶栏而非被一条硬边切断
            // 采样 backdrop 已含背景流光 折叠后透出流光而非黑底
            Spacer(
                Modifier
                    .then(titleBarProgressiveBlur(backdrop = backdrop, tintColor = pageColor))
                    .fillMaxSize(),
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .stableStatusBarsPadding()
                .height(BarHeight),
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                Box(Modifier.height(48.dp), contentAlignment = Alignment.CenterStart) {
                    if (onBack != null) {
                        Box(Modifier.padding(start = 16.5.dp)) {
                            GlassBackButton(
                                onClick = onBack,
                                backdrop = backdrop,
                                icon = backIcon ?: painterResource(R.drawable.ic_back),
                                glassAlpha = { barGlassAlpha.value },
                            )
                        }
                    }
                    SmallTitle(
                        title = smallTitleText,
                        visible = smallTitleVisible,
                        onClick = onTitleClick,
                        onLongClick = onTitleLongClick,
                        isPath = smallTitleText != title,
                    )
                    Row(
                        Modifier.fillMaxSize().padding(end = 6.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 右上角动作键：与左上角返回键同款玻璃圆钮
                        if (onTopAction != null) {
                            val icon = topActionIcon
                                ?: rememberVectorPainter(MiuixIcons.Close)
                            Box(Modifier.padding(end = 10.dp)) {
                                GlassBackButton(
                                    onClick = onTopAction,
                                    backdrop = backdrop,
                                    icon = icon,
                                    contentDescription = topActionDescription,
                                    // 图标不是箭头 不需要返回键那 2dp 光学补偿
                                    iconSize = topActionIconSize,
                                    iconOffset = topActionIconOffset,
                                    glassAlpha = { barGlassAlpha.value },
                                )
                            }
                        }
                        actions()
                    }
                }
            }
        }
    }
}

/**
 * 小标题：大标题完全没入后才出现
 *
 * 单独成函数是为了脱离 ColumnScope —— 直接在 Column 里调 AnimatedVisibility
 * 会和它的 ColumnScope 重载抢隐式接收者 编译期报"cannot be called in this context"
 *
 * 文件选择器里这行显示的是当前路径：
 * 点一下 = 返回上一级目录 长按 = 弹出输入框跳转到指定目录
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmallTitle(
    title: String,
    visible: () -> Boolean,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    isPath: Boolean = false,
) {
    val clickable = onClick != null || onLongClick != null
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 70.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = visible(),
            enter = fadeIn(tween(120, easing = EaseOut)) +
                expandVertically(
                    expandFrom = Alignment.Top,
                    clip = false,
                    animationSpec = tween(120, easing = EaseOut),
                ),
            exit = fadeOut(tween(120, easing = EaseInCirc)) +
                shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    clip = false,
                    animationSpec = tween(120, easing = EaseInCirc),
                ),
        ) {
            Text(
                text = title,
                fontSize = if (isPath) 14.sp else 18.5.sp,
                fontWeight = if (isPath) FontWeight.Medium else FontWeight.Bold,
                color = if (isPath) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.onBackground
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (clickable) {
                    Modifier.combinedClickable(
                        onClick = { onClick?.invoke() },
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier
                },
            )
        }
    }
}

/**
 * 返回键：42dp 玻璃圆钮 按住可拖动 高光跟手
 *
 * 图标换成 Sonify 同款：`res/drawable/ic_back.xml` 那个实心厚箭头
 * （宽高比约 1:1.74 18dp 的框里 Fit 成高 18dp、宽约 10dp）
 * 不再用 Miuix 的细线箭头 两者放在玻璃圆钮里观感差别很明显
 *
 * [glassAlpha] 是玻璃材质的整体透明度：
 * 参照原版的行为 —— **只有小顶栏出现时 顶栏图标才带圆玻璃背景**；
 * 大标题还没折叠时（顶栏透明）退化为纯图标 不画圆底和投影
 *
 * 玻璃与纯图标用的是同一个组件（不靠 if 切换） 否则 alpha 动画过程中
 * 节点会被销毁重建 按钮在动画中闪一下
 *
 * 颜色口径完全对齐 Sonify 的 LiquidTopBarButton：
 * - 容器色 White@10%、边缘光取自适应内容色@55%、模糊半径按背景亮度调制
 * - **图标着色也走自适应**（浅色黑 85% / 深色白 85%），与 Sonify 一致
 *   之前这里锁过主题前景色，是为了绕开"采样窗口混入大标题文字、把图标判成白箭头"
 *   的问题；既然要求与 Sonify 完全对齐，就交回自适应
 */
@Composable
private fun GlassBackButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    icon: Painter,
    contentDescription: String = "返回",
    iconSize: Dp = 18.dp,
    // Sonify 原值：箭头在 42dp 圆钮里左移 2dp 做光学居中
    iconOffset: DpOffset = DpOffset((-2).dp, 0.dp),
    glassAlpha: () -> Float = { 1f },
) {
    LiquidTopBarButton(
        onClick = onClick,
        backdrop = backdrop,
        icon = icon,
        contentDescription = contentDescription,
        iconSize = iconSize,
        iconOffset = iconOffset,
        backdropAlpha = { glassAlpha() },
        shadowAlpha = { glassAlpha() },
        edgeLightAlpha = { glassAlpha() },
        draggable = true,
        adaptiveLuminance = true,
    )
}

/** 折叠三态：大标题透明度 / 顶栏背景是否出现 / 小标题是否出现 */
private class TitleCollapse(
    val alpha: () -> Float,
    val bar: () -> Boolean,
    val small: () -> Boolean,
)

/**
 * 从列表滚动位置算折叠状态
 *
 * 判定用的是「首个 item 相对视口的偏移」：大标题高多少 就有多长的过渡区
 * 所以不同标题长度都是同一条曲线 不会出现"短标题一下就没"
 */
@Composable
private fun rememberTitleCollapse(state: LazyListState): TitleCollapse {
    val statusPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        stableStatusBarHeight().roundToPx()
    }
    val firstItem = rememberUpdatedState {
        state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
            ?.let { Pair(it.offset, it.size) }
    }
    val ready = rememberUpdatedState { state.layoutInfo.totalItemsCount > 0 }

    val ui = remember(state, statusPx) {
        derivedStateOf {
            calculateTitleCollapse(
                firstItemInfo = firstItem.value(),
                isLayoutReady = ready.value(),
                extraPx = 0,
                statusPx = statusPx,
            )
        }
    }
    return remember(ui) {
        TitleCollapse(
            alpha = { ui.value.alpha },
            bar = { ui.value.bar },
            small = { ui.value.small },
        )
    }
}
