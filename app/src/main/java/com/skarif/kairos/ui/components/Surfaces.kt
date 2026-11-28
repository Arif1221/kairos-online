package com.skarif.kairos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.skarif.kairos.ui.MainViewModel
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.Radius
import com.skarif.kairos.ui.theme.T

/*
 * Small building blocks the screens share, so a card, a section title or a progress bar is written
 * once and looks the same everywhere. Colours, radii and the hairline come from the theme tokens
 * (P.colors.cardSoft, P.colors.hairline, Radius), so a change there reaches every screen.
 */

/**
 * The app's card surface: clipped to [radius], filled with [color] and edged with the hairline.
 * Chain click handlers, padding and sharedCard before or after it as usual.
 */
@Composable
fun Modifier.cardSurface(radius: Dp = Radius.card, color: Color = P.colors.cardSoft): Modifier {
    val shape = RoundedCornerShape(radius)
    return this.clip(shape).background(color).border(1.dp, P.colors.hairline, shape)
}

/** A section heading with an optional pill action on the right ("See all"). Side margin is the page margin. */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val c = P.colors
    Row(modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = T.h2, color = c.text)
        if (action != null) {
            Text(
                action,
                Modifier.cardSurface(16.dp).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 12.dp, vertical = 6.dp),
                style = T.small.copy(fontWeight = FontWeight.SemiBold), color = c.text,
            )
        }
    }
}

/** A thin rounded bar filled to [fraction] (0..1): top songs, top artists, any "share of the total". */
@Composable
fun ShareBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 7.dp) {
    val r = RoundedCornerShape(height / 2)
    Box(modifier.fillMaxWidth().height(height).clip(r).background(P.colors.chip)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(height).clip(r).background(color))
    }
}

/** Runs [action] (usually "scroll to the top") when the person taps the dock's [tab] again. */
@Composable
fun OnTabReselected(vm: MainViewModel, tab: String, action: suspend () -> Unit) {
    LaunchedEffect(Unit) { vm.tabReselected.collect { if (it == tab) action() } }
}
