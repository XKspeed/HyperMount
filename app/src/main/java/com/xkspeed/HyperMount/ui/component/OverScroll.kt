package com.xkspeed.HyperMount.ui.component

import androidx.compose.runtime.Stable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScrollModifierNode
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.util.fastAny
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sign
import androidx.compose.runtime.withFrameNanos

/**
 * 过滚动（拉伸回弹）。
 *
 * 原来的曲线写在 miuix 的 `SpringMath` 里，是 `internal` 且没有任何入参，
 * 只能拉出窗口高度的 1/3。这里把同一套实现搬进项目 把"最大拉伸距离"
 * 固定成屏高的 15%——调试页调参入口已经撤掉，手感按调好的这版写死。
 *
 * 只有日志块（主页 / 界面页「运行状态」下面那块）走这套实现；
 * 页面本身的滚动仍旧用 miuix 的 `overScrollVertical`，保持原手感。
 */
private const val MAX_FRACTION = 0.15f

/**
 * 垂直过滚动。与 miuix 的 `overScrollVertical` 行为一致，区别只有两点：
 *
 * 1. 最大拉伸距离是屏高的 15%（[MAX_FRACTION]），可以超过原来的 1/3 屏；
 * 2. 松手时若还停在拉伸状态之外（`offset` 已回位），不会再把余下的甩动速度
 *    喂给弹簧 —— 原实现无条件喂，会把刚回位的内容再踹出去，看着像瞬移。
 *
 * [nestedScrollToParent] 为 false 时（日志块那种自持滚动区），这一层既不把
 * 剩余位移交给外层页面，也不把甩动速度交给外层页面。
 */
@Stable
fun Modifier.mountOverScrollVertical(
    nestedScrollToParent: Boolean = true,
): Modifier = this
    .clipToBounds()
    .then(
        MountOverScrollElement(
            isVertical = true,
            nestedScrollToParent = nestedScrollToParent,
        ),
    )

private data class MountOverScrollElement(
    val isVertical: Boolean,
    val nestedScrollToParent: Boolean,
) : ModifierNodeElement<MountOverScrollNode>() {
    override fun create(): MountOverScrollNode = MountOverScrollNode(
        isVertical = isVertical,
        nestedScrollToParent = nestedScrollToParent,
    )

    override fun update(node: MountOverScrollNode) {
        node.update(
            isVertical = isVertical,
            nestedScrollToParent = nestedScrollToParent,
        )
        node.invalidatePlacement()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "mountOverScrollVertical"
        properties["isVertical"] = isVertical
        properties["nestedScrollToParent"] = nestedScrollToParent
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private class MountOverScrollNode(
    var isVertical: Boolean,
    var nestedScrollToParent: Boolean,
) : DelegatingNode(),
    CompositionLocalConsumerModifierNode,
    LayoutModifierNode,
    NestedScrollConnection {

    private val density: Density
        get() = currentValueOf(LocalDensity)

    private val windowInfo: WindowInfo
        get() = currentValueOf(LocalWindowInfo)

    private val dispatcher = NestedScrollDispatcher()
    private val springEngine = MountSpringEngine()
    private var animationJob: Job? = null
    private val offsetThreshold = 1f

    /** 手指按下 / 拖动中（含鼠标拖动），滚轮不算 */
    private var gestureActive = false

    init {
        delegate(
            SuspendingPointerInputModifierNode {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val active = when (event.type) {
                            PointerEventType.PanStart, PointerEventType.PanMove -> true
                            PointerEventType.PanEnd -> false
                            PointerEventType.Scroll -> gestureActive
                            else -> event.changes.fastAny { it.pressed }
                        }
                        // 没有触发 fling 的松手（例如到边界后慢慢抬起）走这里收尾
                        if (gestureActive && !active &&
                            animationJob?.isActive != true && abs(offset) > offsetThreshold
                        ) {
                            startSpringAnimation()
                        }
                        // 已经进入过滚动（列表到边、只剩拉伸）时，把余下的位移
                        // 从事件流里吃掉：列表自身这时不再 consume，外层页面／外层
                        // 列表就会把同一批 move 事件当成自己的拖动，表现为"整页跟着滚"。
                        // 本节点在 modifier 链上比内层 scrollable 靠外、比外层页面靠内，
                        // 所以这里 consume 只挡外层，不影响内层正常滚动。
                        if (gestureActive && abs(offset) > offsetThreshold) {
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                        }
                        gestureActive = active
                    }
                }
            },
        )
    }

    private var lastPlacedOffset = 0f
    var offset = 0f
        private set(value) {
            if (field != value) {
                field = value
                // placement 按像素取整，只有取整值变了才需要重排
                val rounded = round(value)
                if (rounded != lastPlacedOffset) {
                    lastPlacedOffset = rounded
                    if (isAttached) invalidatePlacement()
                }
            }
        }

    private var rawTouchAccumulation = 0f
    private var scrollRange: Float = 0f
    private var cachedScrollRangeDensity: Density? = null
    private var cachedScrollRangeWindowInfo: WindowInfo? = null

    override fun onAttach() {
        super.onAttach()
        updateScrollRange()
        delegate(nestedScrollModifierNode(this, dispatcher))
    }

    override fun onDetach() {
        super.onDetach()
        gestureActive = false
        animationJob?.cancel()
        animationJob = null
        offset = 0f
        rawTouchAccumulation = 0f
    }

    fun update(
        isVertical: Boolean,
        nestedScrollToParent: Boolean,
    ) {
        val rangeChanged = this.isVertical != isVertical
        this.isVertical = isVertical
        this.nestedScrollToParent = nestedScrollToParent
        if (rangeChanged && isAttached) updateScrollRange()
    }

    private fun updateScrollRange() {
        val currentDensity = density
        val currentWindowInfo = windowInfo
        if (currentDensity == cachedScrollRangeDensity &&
            currentWindowInfo == cachedScrollRangeWindowInfo
        ) {
            return
        }
        cachedScrollRangeDensity = currentDensity
        cachedScrollRangeWindowInfo = currentWindowInfo
        scrollRange = with(currentDensity) {
            if (isVertical) {
                currentWindowInfo.containerDpSize.height.toPx()
            } else {
                currentWindowInfo.containerDpSize.width.toPx()
            }
        }
    }

    private fun resetState() {
        offset = 0f
        rawTouchAccumulation = 0f
    }

    /**
     * 三次曲线 `x - x² + x³/3`，x=1 时是 1/3。
     *
     * 乘 `3 × maxFraction` 之后，x=1 的位移正好是 `range × maxFraction`：
     * maxFraction = 1/3 时与原实现逐像素一致，调到 1.0 就是能拉出整整一屏。
     */
    private fun dampedDistance(normalized: Float, range: Float): Float {
        val x = normalized.coerceIn(0f, 1f)
        val factor = x - x * x + x * x * x / 3f
        return factor * range * (3f * MAX_FRACTION)
    }

    /** 由位移反解手指走过的距离：曲线单调，二分足够且比解析解稳 */
    private fun touchDistanceFor(absOffset: Float, range: Float): Float {
        val maxOffset = range * MAX_FRACTION
        if (absOffset >= maxOffset) return range
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2f
            if (dampedDistance(mid, range) < absOffset) lo = mid else hi = mid
        }
        return ((lo + hi) / 2f) * range
    }

    private fun applyDrag(delta: Float) {
        if (delta == 0f) return
        rawTouchAccumulation += delta
        rawTouchAccumulation = rawTouchAccumulation.coerceIn(-scrollRange, scrollRange)
        val normalized = min(abs(rawTouchAccumulation) / scrollRange, 1f)
        offset = sign(rawTouchAccumulation) * dampedDistance(normalized, scrollRange)
    }

    /** 拖动接管与弹簧的衔接：把当前位移换算回"手指累计量" */
    private fun syncRawAccumulationFromOffset() {
        rawTouchAccumulation = sign(offset) * touchDistanceFor(abs(offset), scrollRange)
    }

    private fun unwindStaleOffset(consumedDelta: Float) {
        if (abs(offset) <= offsetThreshold || consumedDelta == 0f) return
        if (rawTouchAccumulation == 0f) syncRawAccumulationFromOffset()
        if (sign(consumedDelta) != sign(rawTouchAccumulation)) return
        if (abs(rawTouchAccumulation) <= abs(consumedDelta)) {
            resetState()
        } else {
            applyDrag(-consumedDelta)
        }
    }

    private fun startSpringAnimation(initialVelocity: Float = 0f) {
        if (abs(offset) <= offsetThreshold && initialVelocity == 0f) {
            resetState()
            return
        }
        animationJob?.cancel()
        animationJob = coroutineScope.launch {
            springEngine.runSettleAnimation(
                startValue = offset,
                initialVelocity = initialVelocity,
                onFrame = { currentPos -> offset = currentPos },
                onSettle = { if (abs(offset) <= offsetThreshold) resetState() },
            )
        }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        updateScrollRange()
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                if (isVertical) {
                    translationY = round(offset)
                } else {
                    translationX = round(offset)
                }
                clip = true
            }
        }
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!isAttached) return Offset.Zero

        val parentConsumed = if (nestedScrollToParent) {
            dispatcher.dispatchPreScroll(available, source)
        } else {
            Offset.Zero
        }

        if (source != NestedScrollSource.UserInput || !gestureActive) {
            // 独立滚动区且已经在拉伸态：惯性尾巴全吃掉，不允许漏给外层页面
            if (!nestedScrollToParent && abs(offset) > offsetThreshold) return available
            return parentConsumed
        }

        val realAvailable = available - parentConsumed
        val delta = if (isVertical) realAvailable.y else realAvailable.x
        if (delta == 0f) return parentConsumed

        // 拖住弹簧：从当前位移反推手指累计量，避免接管瞬间跳一下
        if (animationJob?.isActive == true) syncRawAccumulationFromOffset()
        animationJob?.cancel()

        if (abs(offset) <= offsetThreshold || sign(delta) == sign(rawTouchAccumulation)) {
            return parentConsumed
        }

        // 往回推：先把已拉伸的部分顶回去，再交给子列表
        val actualConsumed = if (abs(rawTouchAccumulation) <= abs(delta)) {
            -rawTouchAccumulation
        } else {
            delta
        }
        if (abs(rawTouchAccumulation) <= abs(delta)) {
            resetState()
        } else {
            applyDrag(actualConsumed)
        }
        return if (isVertical) {
            Offset(parentConsumed.x, actualConsumed + parentConsumed.y)
        } else {
            Offset(actualConsumed + parentConsumed.x, parentConsumed.y)
        }
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (!isAttached) return Offset.Zero

        val parentConsumed = if (nestedScrollToParent) {
            dispatcher.dispatchPostScroll(consumed, available, source)
        } else {
            Offset.Zero
        }

        if (source != NestedScrollSource.UserInput || !gestureActive) {
            if (animationJob?.isActive != true) {
                unwindStaleOffset(if (isVertical) consumed.y else consumed.x)
            }
            // 同上：拉伸态下的惯性余量由自己兜住
            if (!nestedScrollToParent && abs(offset) > offsetThreshold) return available
            return parentConsumed
        }

        val realAvailable = available - parentConsumed
        val delta = if (isVertical) realAvailable.y else realAvailable.x
        val consumedDelta = if (isVertical) consumed.y else consumed.x
        if (delta == 0f && consumedDelta == 0f) return parentConsumed

        if (animationJob?.isActive == true) syncRawAccumulationFromOffset()
        animationJob?.cancel()
        unwindStaleOffset(consumedDelta)

        applyDrag(delta)
        return if (isVertical) {
            Offset(parentConsumed.x, available.y)
        } else {
            Offset(available.x, parentConsumed.y)
        }
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!isAttached) return Velocity.Zero

        val parentConsumed = if (nestedScrollToParent) {
            dispatcher.dispatchPreFling(available)
        } else {
            Velocity.Zero
        }
        val realAvailable = available - parentConsumed
        val velocity = if (isVertical) realAvailable.y else realAvailable.x

        if (abs(offset) <= offsetThreshold) {
            // 没在拉伸态：这段速度归列表自己 fling。
            // nestedScrollToParent=false 时也不外抛，实测让列表照常滑到底。
            return if (nestedScrollToParent) parentConsumed else Velocity.Zero
        }

        animationJob?.cancel()
        startSpringAnimation(velocity)
        val handled = if (nestedScrollToParent && sign(velocity) != sign(offset)) {
            // 往回甩：衰减一半，避免回弹过头
            realAvailable / 2.13333f
        } else {
            realAvailable
        }
        return parentConsumed + if (isVertical) {
            Velocity(0f, handled.y)
        } else {
            Velocity(handled.x, 0f)
        }
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (!isAttached) return Velocity.Zero

        val parentConsumed = if (nestedScrollToParent) {
            dispatcher.dispatchPostFling(consumed, available)
        } else {
            Velocity.Zero
        }
        val realAvailable = available - parentConsumed
        val velocity = (if (isVertical) realAvailable.y else realAvailable.x) / 1.53333f

        // 已经回位就别再喂弹簧：原实现无条件 startSpringAnimation，
        // 会把刚回位的这块内容再踹出去一次，看起来像"松手瞬间页面瞬移"
        if (abs(offset) <= offsetThreshold) {
            return if (nestedScrollToParent) parentConsumed else Velocity.Zero
        }

        animationJob?.cancel()
        startSpringAnimation(velocity)
        return parentConsumed + if (isVertical) {
            Velocity(0f, velocity)
        } else {
            Velocity(velocity, 0f)
        }
    }
}

// ---- 弹簧：与 miuix 的 SpringEngine 同一套临界阻尼实现 ----

private class MountSpringOperator(dampingRatio: Float, naturalPeriod: Float) {
    private val dampingCoefficient: Double
    private val stiffnessOverMass: Double

    init {
        val angularFrequency = (2.0 * PI) / naturalPeriod
        stiffnessOverMass = angularFrequency * angularFrequency
        dampingCoefficient = 2.0 * dampingRatio * angularFrequency
    }

    fun updateVelocity(
        currentVelocity: Double,
        deltaTime: Float,
        currentPosition: Double,
        targetPosition: Double,
    ): Double {
        val velocityDecayFactor = 1.0 - dampingCoefficient * deltaTime
        val velocityIncreaseFromSpring =
            stiffnessOverMass * (targetPosition - currentPosition) * deltaTime
        return currentVelocity * velocityDecayFactor + velocityIncreaseFromSpring
    }
}

private class MountSpringEngine {
    private var springOperator: MountSpringOperator? = null
    var velocity: Double = 0.0
    var currentPos: Double = 0.0
    private var targetPos: Double = 0.0
    private var initialPos: Double = 0.0
    private var initialVelocity: Double = 0.0

    private fun isAtEquilibrium(): Boolean {
        if (initialPos < targetPos && currentPos > targetPos) return true
        if (initialPos <= targetPos || currentPos >= targetPos) {
            return (initialPos == targetPos && sign(initialVelocity) != sign(currentPos)) ||
                abs(currentPos - targetPos) < 1.0
        }
        return true
    }

    fun start(startValue: Float, targetValue: Float, initialVel: Float) {
        currentPos = startValue.toDouble()
        initialPos = startValue.toDouble()
        targetPos = targetValue.toDouble()
        velocity = initialVel.toDouble()
        initialVelocity = initialVel.toDouble()
        springOperator = MountSpringOperator(
            dampingRatio = 1.0f,
            naturalPeriod = if (abs(initialVel) > 5000.0) 0.55f else 0.4f,
        )
    }

    fun step(deltaTime: Float): Boolean {
        val operator = springOperator ?: return false
        val dt = deltaTime.coerceIn(0.001f, 0.016f)
        velocity = operator.updateVelocity(velocity, dt, currentPos, targetPos)
        currentPos += dt * velocity
        if (isAtEquilibrium()) {
            currentPos = targetPos
            velocity = 0.0
            return true
        }
        return false
    }

    suspend fun runSettleAnimation(
        startValue: Float,
        targetValue: Float = 0f,
        initialVelocity: Float,
        onFrame: (Float) -> Unit,
        onSettle: () -> Unit,
    ) {
        start(startValue, targetValue, initialVelocity)
        var lastFrameTimeNanos = -1L
        var finished = false
        try {
            while (!finished && currentCoroutineContext().isActive) {
                finished = withFrameNanos { frameTimeNanos ->
                    if (lastFrameTimeNanos == -1L) {
                        lastFrameTimeNanos = frameTimeNanos
                        return@withFrameNanos false
                    }
                    val dt = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f
                    lastFrameTimeNanos = frameTimeNanos
                    val done = step(dt)
                    onFrame(currentPos.toFloat())
                    done
                }
            }
        } finally {
            onSettle()
        }
    }
}
