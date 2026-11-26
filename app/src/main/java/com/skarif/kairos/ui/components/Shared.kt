@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.skarif.kairos.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role

/** The app-wide shared-transition scope (set once around the NavHost). */
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The animation scope of the screen or overlay currently being composed. */
val LocalNavScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable
fun ProvideNavScope(scope: AnimatedVisibilityScope, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalNavScope provides scope, content = content)

/** Same response as page pushes (Motion), with a hint of overshoot so the card lands. */
private val CardSpring = BoundsTransform { _, _ -> com.skarif.kairos.ui.motion.Motion.ios(0.42f, 0.88f, androidx.compose.ui.geometry.Rect(0.5f, 0.5f, 0.5f, 0.5f)) }

/**
 * Container transform: an element with the same [key] on the old and the new screen morphs
 * from one to the other (a cover card growing into the album page, the mini player into
 * Now Playing). Does nothing where no transition scope is available.
 */
@Composable
fun Modifier.sharedCard(key: String, shape: Shape = RectangleShape): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val nav = LocalNavScope.current ?: return this
    return with(shared) {
        this@sharedCard.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = nav,
            boundsTransform = CardSpring,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}

/** Tappable card: shrinks a little under the finger, ripples, then runs [onClick]. [onLongClick] opens its menu. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Modifier.cardClick(label: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this
        .pressScale(interaction, 0.96f)
        .then(
            if (onLongClick == null) Modifier.clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClickLabel = label, onClick = onClick)
            else Modifier.combinedClickable(
                interactionSource = interaction, indication = ripple(), role = Role.Button, onClickLabel = label, onClick = onClick,
                onLongClickLabel = "More options", onLongClick = onLongClick,
            ),
        )
}
