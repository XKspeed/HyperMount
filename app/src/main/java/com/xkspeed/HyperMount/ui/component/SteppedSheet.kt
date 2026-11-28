package com.xkspeed.HyperMount.ui.component

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.xkspeed.HyperMount.ui.SheetFeedback
import com.xkspeed.HyperMount.ui.UiFeedback
import top.yukonga.miuix.kmp.layout.BottomSheetDefaults
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/** 能拉到的最高处：屏幕高度的 80% */
private const val SHEET_MAX_FRACTION = 0.80f

/**
 * 初始张开的比例。
 *
 * 面板本身没有「最小高度」—— 内容自己撑多高就是多高，短列表就矮矮一条。
 * 这个 0.5 是给内容加的天花板：内容比半屏还高时，先露半屏，剩下的往上滑看。
 */
private const val SHEET_START_FRACTION = 0.50f

/**
 * 已经缩到底、还继续往下拖这么多就关面板。
 *
 * 滚动容器在顶部不会消费下拉位移，但它的过滚动特效会把这截吃掉，
 * 传不到面板自己的关闭手势 —— 所以由 [SteppedBottomSheet] 自己攒位移判关闭。
 */
private val PULL_DOWN_TO_DISMISS = 72.dp

/**
 * 高度可连续调节的底部弹出（全项目统一）
 *
 * 高度 = min(内容自然高度, 当前比例 × 屏高)，比例在 50% 与 80% 之间无极滑动：
 * - 打开时停在「内容高度，最多半屏」
 * - 内容比半屏矮时，面板就是内容那么高，不再有硬性留白
 * - 上滑连续撑高，到 80% 封顶，之后上滑才交给内容自己滚
 * - 内容已经贴顶时下拉，连续收回；缩到起点再往下就关面板
 *
 * 只有「被 80%（或过程中某一比例）卡住」时才有得缩：内容本来就矮、面板已是自然高度，
 * 下拉直接走关闭，不会先空滑一段。
 *
 * @param title 库自带标题栏的文字。要往标题行左右塞按钮、或者让标题行变形
 *   （比如展开成整行搜索栏）时改用下面的 [header]
 * @param header 自绘标题行，替代 [title]。回调参数是「是否已经拉到最高」，
 *   用来做只在高档出现的样式。它浮在内容之上、不跟着列表滚，
 *   内容会从它底下滚过去 —— 顶栏的模糊/玻璃才采得到东西
 * @param headerHeight [header] 的高度。内容首项会空出这段，滚起来才不会被标题行压住第一行
 * @param headerLift 把 [header] 往上抬多少，用来吃掉库拖拽把手下方的留白
 *   （把手下沿到内容起点有 10dp 空白，抬这么多按钮才能真正贴着手柄）。
 *   内容首项的空高会同步减掉这一段
 * @param hideHandle 隐藏库自带的拖拽指示条（拖拽手势仍然可用）。
 *   需要把条形画在更靠上的位置时用它 —— 库的条固定在手柄区正中，
 *   标题行一旦上移就会把条盖住
 * @param contentPadding 内容四周留白。高度受比例约束，这里的 padding 算在面板高度内
 * @param scrollResetKey 同一个面板里换内容（比如列表 → 单条详情）时把滚动位置归零。
 *   高度差一大截时，新内容会带着上一屏的偏移停在半截，看着像"瞬移"
 * @param headerDragEnabled 标题行能不能拖着调高度。标题行里有输入框时关掉，
 *   否则横向选词会被竖直拖动抢走
 */
@Composable
fun SteppedBottomSheet(
    show: Boolean,
    title: String? = null,
    onDismissRequest: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    header: (@Composable (expanded: Boolean) -> Unit)? = null,
    headerHeight: Dp = 0.dp,
    headerLift: Dp = 0.dp,
    hideHandle: Boolean = false,
    headerDragEnabled: Boolean = true,
    scrollResetKey: Any? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val density = LocalDensity.current
    val screenPx = with(density) { screenHeight.toPx() }
    val scrollState = rememberScrollState()

    // 当前允许的最大高度占比，跟着手指连续变
    var fraction by remember { mutableFloatStateOf(SHEET_START_FRACTION) }
    // 面板实际被撑到多高（px）：用来分辨「是内容顶住的」还是「比例顶住的」
    var actualPx by remember { mutableStateOf(0) }
    // 缩到底以后继续下拉累积的距离，够长了就关面板
    var pullDown by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(show) {
        if (show) {
            fraction = SHEET_START_FRACTION
            pullDown = 0f
            scrollState.scrollTo(0)
        }
    }

    // 换内容时回到顶部：不移这一下，详情会停在列表那一屏的偏移上
    LaunchedEffect(scrollResetKey) {
        if (show) scrollState.scrollTo(0)
    }

    // 面板是独立 window，页面层那条提示会被它整个盖住：
    // 开着的时候让页面那处宿主退场，改由下面窗口内这份接力
    DisposableEffect(show) {
        if (show) UiFeedback.onSheetShown()
        onDispose { if (show) UiFeedback.onSheetHidden() }
    }

    WindowBottomSheet(
        show = show,
        // 自绘标题行时把库的标题栏整个让空，否则两行会叠在一起
        title = if (header == null) title else null,
        // 和页面同色：库默认给的是 background（中性底色），莫奈取色下跟页面的
        // surface 有色差，看着就像"弹窗没跟主题走"
        backgroundColor = MiuixTheme.colorScheme.surface,
        dragHandleColor = if (hideHandle) Color.Transparent else BottomSheetDefaults.dragHandleColor(),
        onDismissRequest = onDismissRequest,
    ) {
        // 必须在 content 内部取：这是库在里面 provides 的，外面拿不到
        val dismiss: (() -> Unit)? = LocalDismissState.current
        val pullThreshold = with(density) { PULL_DOWN_TO_DISMISS.toPx() }

        /**
         * 一套比例调整逻辑，两个入口共用：
         * - 列表贴顶时的下拉、上滑 → nestedScroll（在列表上拖）
         * - 标题行上的拖动 → 自己那条 draggable（标题行是浮层，列表收不到那里的手势）
         *
         * 返回 true 表示这段位移真的改了 [fraction]（nestedScroll 据此决定消不消费）；
         * 累积够了下拉关闭时返回 false —— 那截位移本来就要让给列表的过滚动特效。
         */
        val dragBy: (Float) -> Boolean = { dy ->
            // 面板已经等于内容高度，意味着再缩也只能缩掉内容
            val contentBound = actualPx > 0 && actualPx < fraction * screenPx - 2f
            when {
                dy < 0f -> {
                    pullDown = 0f
                    if (contentBound || fraction >= SHEET_MAX_FRACTION) {
                        false
                    } else {
                        fraction = (fraction - dy / screenPx).coerceAtMost(SHEET_MAX_FRACTION)
                        true
                    }
                }
                dy > 0f && scrollState.value == 0 -> {
                    if (!contentBound && fraction > SHEET_START_FRACTION) {
                        // 先把已经撑开的部分收回去
                        pullDown = 0f
                        fraction = (fraction - dy / screenPx).coerceAtLeast(SHEET_START_FRACTION)
                        true
                    } else {
                        // 没得缩了，继续下拉就是要关面板
                        pullDown += dy
                        if (pullDown >= pullThreshold) {
                            pullDown = 0f
                            if (dismiss != null) dismiss() else onDismissRequest()
                        }
                        false
                    }
                }
                else -> false
            }
        }
        val currentDragBy by rememberUpdatedState(dragBy)

        val nested = remember(density, pullThreshold) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                    if (currentDragBy(available.y)) available else Offset.Zero

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                    Velocity.Zero
            }
        }

        // 标题行那块是浮层，压在手柄和列表之上：里面每颗按钮都会吃掉命中，
        // 列表的 scrollable 和库自带的手柄手势都收不到，表现就是"按住图标拖不动面板"。
        // 这里在标题行容器上自挂一条竖直拖动，和上面那套比例逻辑共用
        val headerDrag = rememberDraggableState { delta -> dragBy(delta) }

        Box(
            Modifier
                .fillMaxWidth()
                .nestedScroll(nested)
                // 高度不写死：内容矮就按内容高，内容多才涨到比例上限
                .heightIn(max = screenHeight * fraction)
                .onSizeChanged { actualPx = it.height }
                .navigationBarsPadding(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(contentPadding),
            ) {
                // 标题行是浮层，这里空出它的高度：第一项从它下面开始，
                // 但整块仍然可以滚进它的模糊层里（滚上去就被糊掉）
                val gap = (headerHeight - headerLift).coerceAtLeast(0.dp)
                if (header != null && gap > 0.dp) {
                    Spacer(Modifier.height(gap))
                }
                content()
            }
            if (header != null) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        // 往手柄方向抬一点，按钮才能真的贴着拖拽条
                        .offset(y = -headerLift)
                        .height(headerHeight)
                        .draggable(
                            state = headerDrag,
                            orientation = Orientation.Vertical,
                            enabled = headerDragEnabled,
                            onDragStopped = { pullDown = 0f },
                        ),
                ) {
                    header(fraction >= SHEET_MAX_FRACTION)
                }
            }
            // 提示条：画在面板自己的窗口里、贴面板底边。页面那份压不过这个 window，
            // 只能在这里再挂一份；浮在内容之上，列表滚到底也盖不住它
            SheetFeedback(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
            )
        }
    }
}
