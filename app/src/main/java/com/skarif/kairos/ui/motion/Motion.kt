package com.skarif.kairos.ui.motion

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.unveilIn
import androidx.compose.animation.veilOut
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import kotlin.math.PI

/**
 * Kairos's motion, kept small on purpose.
 *
 * Rules (the iOS ones):
 * 1. Things that move are springs, described like SwiftUI: a response (seconds, how quick it
 *    feels) and a damping fraction (1 = no overshoot). Springs keep a gesture's velocity and can
 *    be interrupted at any frame.
 * 2. Big surfaces (pages, the player) never bounce. Small things may, a little.
 * 3. Fades and colour changes are short and linear; they are not physical.
 * 4. Only transforms and alpha animate, read in the draw phase. Nothing recomposes per frame.
 * 5. No decoration: no staggered entrances, nothing that runs when no one is touching the app.
 *
 * "Remove animations" in Android's accessibility settings scales all of it to zero.
 */
object Motion {
    /** stiffness = (2π / response)²; damping is Compose's damping ratio. */
    fun <T> ios(response: Float, damping: Float = 1f, threshold: T? = null) = spring(
        dampingRatio = damping,
        stiffness = (2 * PI / response).let { (it * it).toFloat() },
        visibilityThreshold = threshold,
    )

    /** Pages and the player sheet: UINavigationController / sheet timing, no overshoot. */
    fun <T> sheet() = ios<T>(0.4f, 1f)

    /** Mid-size things settling into place: covers, cards, the mini player (SwiftUI .smooth). */
    fun <T> spatial() = ios<T>(0.4f, 0.9f)

    /** Small, quick things: icons, chips, toggles (SwiftUI .snappy). */
    fun <T> snappy() = ios<T>(0.3f, 0.86f)

    /** Release after a press, play/pause: a little life (SwiftUI .bouncy, toned down). */
    fun <T> bouncy() = ios<T>(0.35f, 0.7f)

    /** Following a finger: nearly instant. */
    fun <T> interactive() = ios<T>(0.12f, 1f)

    fun <T> fadeIn(): FiniteAnimationSpec<T> = tween(180, easing = LinearEasing)
    fun <T> fadeOut(): FiniteAnimationSpec<T> = tween(120, easing = LinearEasing)

    private val page = ios(0.4f, 1f, IntOffset(1, 1))
    private val dim = Color.Black.copy(alpha = 0.22f)

    // ---- Page transitions. Pages are opaque (see Backdrop), so they can overlap like iOS.
    // One set for the whole NavHost; each picks the right motion from where it goes.

    /** The dock's tabs. Moving between them is a tab switch, not a push. */
    val TABS = setOf("home", "library", "stats", "search")
    private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabSwitch() =
        initialState.destination.route in TABS && targetState.destination.route in TABS
    private val tabFade = tween<Float>(140, easing = LinearEasing)

    /** Push: the new page slides in from the right edge over the old one. Tabs: it fades in on top. */
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (tabSwitch()) fadeIn(tabFade) else slideInHorizontally(page) { it }
    }

    /** The page underneath slides back a third and darkens. Tabs: it stays put under the new one. */
    @OptIn(ExperimentalAnimationApi::class)
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (tabSwitch()) ExitTransition.KeepUntilTransitionsFinished
        else slideOutHorizontally(page) { -it / 3 } + veilOut(tween(400, easing = LinearEasing), dim)
    }

    /**
     * Pop, the mirror image; with predictive back it follows your thumb. On a pop the leaving page
     * is drawn on top, so for tabs it is the one that fades.
     */
    @OptIn(ExperimentalAnimationApi::class)
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (tabSwitch()) EnterTransition.None
        else slideInHorizontally(page) { -it / 3 } + unveilIn(tween(400, easing = LinearEasing), dim)
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (tabSwitch()) fadeOut(tabFade) else slideOutHorizontally(page) { it }
    }
}

/**
 * One rule for every swipe: the player, the cover, the mini player and list rows. A swipe counts
 * when it travels [DISTANCE_DP], or when it is a flick of at least [FLING_DP_PER_S] that moved at
 * least [MIN_FLING_DP] the same way.
 */
object Swipe {
    const val DISTANCE_DP = 72f
    const val FLING_DP_PER_S = 800f
    const val MIN_FLING_DP = 16f

    /** 1 positive, -1 negative, 0 none. [offsetPx] distance moved, [velocityPx] px/s. */
    fun decide(offsetPx: Float, velocityPx: Float, density: Float): Int {
        val dist = DISTANCE_DP * density
        val fling = FLING_DP_PER_S * density
        val min = MIN_FLING_DP * density
        return when {
            offsetPx >= dist || (velocityPx >= fling && offsetPx >= min) -> 1
            offsetPx <= -dist || (velocityPx <= -fling && offsetPx <= -min) -> -1
            else -> 0
        }
    }
}

/**
 * True while the player sheet is moving. Work triggered by a song change (recolouring, filling a
 * long queue) waits for it, so it never lands on an animation frame.
 */
object PlayerMotion {
    private val state = androidx.compose.runtime.mutableStateOf(false)
    var animating: Boolean
        get() = state.value
        set(v) { state.value = v }
}
