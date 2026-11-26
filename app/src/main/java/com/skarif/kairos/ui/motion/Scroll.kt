package com.skarif.kairos.ui.motion

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/*
 * Scrolling, iOS style.
 * - [rememberIosFling]: a fling that glides longer and slows evenly, like UIScrollView.
 * - [RubberBand]: pulling past either end stretches the content with growing resistance and
 *   springs back; a fling that hits the end overshoots a little and settles.
 *
 * The rubber band is a real Compose [OverscrollEffect], so the list itself hands it exactly the
 * scroll it couldn't use and the fling speed left over. (The old version listened from the
 * outside through nested scrolling, and a leftover stretch could swallow a whole fling: the list
 * stopped dead and needed a slow drag to get going again.)
 *
 * Every scrollable in the app gets it through [IosOverscrollFactory]; screens whose header reacts
 * to the pull (large titles growing) create one with [rememberRubberBand] and read [RubberBand.pull].
 */

/** iOS decelerates by 0.998 per ms; Compose's exponential decay × 0.5 is about the same. */
private val IosDecay: DecayAnimationSpec<Float> = exponentialDecay(frictionMultiplier = 0.5f)

private class IosFling(private val decay: DecayAnimationSpec<Float>) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (abs(initialVelocity) < 1f) return initialVelocity
        var left = initialVelocity
        var last = 0f
        AnimationState(0f, initialVelocity).animateDecay(decay) {
            val delta = value - last
            val used = scrollBy(delta)
            last = value
            left = velocity
            // Reached the end: stop and hand the remaining speed to the rubber band.
            if (abs(delta - used) > 0.5f) cancelAnimation()
        }
        return if (abs(left) < 1f) 0f else left
    }
}

@Composable
fun rememberIosFling(): FlingBehavior = remember { IosFling(IosDecay) }

/** The stretch at the ends of a scrollable. [pull] is in pixels; positive = pulled down/right. */
@Stable
class RubberBand : OverscrollEffect {
    private var stretch by mutableStateOf(Offset.Zero)
    private var sizePx = Offset(2400f, 2400f)

    /** Vertical stretch; read it while drawing (large titles grow with it). */
    val pull: Float get() = stretch.y

    override val isInProgress: Boolean get() = stretch != Offset.Zero

    /** iOS's rubber-band curve, applied step by step: the further it's pulled, the less it moves. */
    private fun resist(current: Float, delta: Float, dimension: Float): Float {
        if (delta == 0f) return 0f
        val factor = (1f - (abs(current) / dimension).coerceIn(0f, 0.95f)) * 0.55f
        return delta * factor
    }

    /** How much of [d] undoes the stretch [p] (scrolling back first un-stretches). */
    private fun relax(p: Float, d: Float): Float =
        if (p == 0f || d == 0f || sign(d) == sign(p)) 0f else if (abs(d) >= abs(p)) -p else d

    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
        // Flings scroll normally; what they can't use comes back to applyToFling as speed.
        if (source != NestedScrollSource.UserInput) return performScroll(delta)
        val undo = Offset(relax(stretch.x, delta.x), relax(stretch.y, delta.y))
        var s = stretch + undo
        val rest = delta - undo
        val used = performScroll(rest)
        val left = rest - used
        s += Offset(resist(s.x, left.x, sizePx.x), resist(s.y, left.y, sizePx.y))
        stretch = s.snapTiny()
        return delta
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        if (stretch != Offset.Zero) {
            // A stretch can be left behind when a touch interrupts the spring-back (the touch
            // cancels it mid-way). If this fling heads back into the content, it must still scroll:
            // relax the band alongside it. Before, the fling went into the spring-back only and the
            // list stopped dead under the finger.
            if (headsIntoContent(stretch.x, velocity.x) || headsIntoContent(stretch.y, velocity.y)) {
                coroutineScope {
                    launch { settle(0f, 0f, Motion.ios(0.3f, 1f)) }
                    performFling(velocity)
                }
                return
            }
            // Let go while stretched toward the end: spring back, carrying the finger's speed.
            settle(velocity.x, velocity.y, Motion.ios(0.4f, 1f))
            return
        }
        val left = performFling(velocity)
        // A fling ran into the end: overshoot by what was left of its speed, then settle.
        if (abs(left.x) > 50f || abs(left.y) > 50f) {
            settle((left.x * 0.35f).coerceIn(-6000f, 6000f), (left.y * 0.35f).coerceIn(-6000f, 6000f), Motion.ios(0.5f, 1f))
        }
    }

    /** A fling away from the end it is stretched past (stretch and speed point opposite ways). */
    private fun headsIntoContent(pulled: Float, velocity: Float) =
        pulled != 0f && abs(velocity) > 50f && sign(velocity) != sign(pulled)

    private suspend fun settle(vx: Float, vy: Float, spec: androidx.compose.animation.core.AnimationSpec<Float>) = coroutineScope {
        launch { animate(stretch.x, 0f, vx, spec) { v, _ -> stretch = Offset(v, stretch.y) } }
        launch { animate(stretch.y, 0f, vy, spec) { v, _ -> stretch = Offset(stretch.x, v) } }
    }

    private fun Offset.snapTiny() = Offset(if (abs(x) < 0.5f) 0f else x, if (abs(y) < 0.5f) 0f else y)

    /** Moves the content by the stretch. Changing it only updates the layer, never relayouts. */
    private inner class Node : Modifier.Node(), LayoutModifierNode {
        override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
            val p = measurable.measure(constraints)
            sizePx = Offset(p.width.toFloat().coerceAtLeast(1f), p.height.toFloat().coerceAtLeast(1f))
            return layout(p.width, p.height) {
                p.placeWithLayer(0, 0) { translationX = stretch.x; translationY = stretch.y }
            }
        }
    }

    override val node: DelegatableNode = Node()
}

@Composable
fun rememberRubberBand(): RubberBand = remember { RubberBand() }

/** Gives every scrollable in the app the iOS rubber band. Provided at the app root. */
object IosOverscrollFactory : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = RubberBand()
    override fun hashCode(): Int = 0x105
    override fun equals(other: Any?): Boolean = other === this
}
