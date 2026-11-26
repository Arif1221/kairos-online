package com.skarif.kairos.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.T

/** Which hints were closed; kept across launches. Settings can bring them all back. */
object Hints {
    private fun prefs(context: Context) = context.getSharedPreferences("hints", Context.MODE_PRIVATE)
    fun seen(context: Context, key: String) = prefs(context).getBoolean(key, false)
    fun dismiss(context: Context, key: String) = prefs(context).edit().putBoolean(key, true).apply()
    fun resetAll(context: Context) = prefs(context).edit().clear().apply()
}

/**
 * A short "what is this" card at the top of a screen, until it's closed. Plain text on the
 * card colour: it explains, it doesn't advertise.
 */
@Composable
fun HintCard(key: String, title: String, lines: List<String>, modifier: Modifier = Modifier, inset: androidx.compose.ui.unit.Dp = 18.dp) {
    val ctx = LocalContext.current
    var shown by remember(key) { mutableStateOf(!Hints.seen(ctx, key)) }
    if (!shown) return
    val c = P.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = inset).clip(RoundedCornerShape(22.dp)).background(c.card).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Ic.Info, null, tint = c.accent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = T.title.copy(fontSize = 15.sp), color = c.text)
            lines.forEach { Text(it, style = T.small, color = c.muted) }
        }
        CircleButton(Ic.Close, "Got it, hide this tip", { Hints.dismiss(ctx, key); shown = false }, size = 36.dp, container = c.chip, content = c.text, iconSize = 16.dp)
    }
}
