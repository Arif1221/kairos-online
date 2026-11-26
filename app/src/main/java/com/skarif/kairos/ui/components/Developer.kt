package com.skarif.kairos.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skarif.kairos.R
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.T

/** Who makes Kairos, and where to find him. One place, so Settings and the welcome screens agree. */
object Developer {
    const val NAME = "SK Arif Hossain"
    const val ROLE = "Developer · B.Tech IT, West Bengal"
    val links = listOf(
        "Instagram" to "https://www.instagram.com/seeking.starlight",
        "GitHub" to "https://github.com/Arif1221",
        "LinkedIn" to "https://www.linkedin.com/in/skarifhossain-it",
    )
}

@Composable
private fun DeveloperPhoto(size: androidx.compose.ui.unit.Dp) {
    Image(painterResource(R.drawable.developer), Developer.NAME, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
}

/** Settings: photo, name, a line about the app, and links. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeveloperCard() {
    val c = P.colors
    val uri = LocalUriHandler.current
    Card(Modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            DeveloperPhoto(60.dp)
            Column(Modifier.weight(1f)) {
                Text("MADE BY", style = T.small.copy(fontSize = 11.sp, letterSpacing = 1.sp), color = c.muted)
                Text(Developer.NAME, style = T.h2, color = c.text)
                Text(Developer.ROLE, style = T.small, color = c.muted)
            }
        }
        Text(
            "Kairos is designed, built and maintained by one person. Ideas, bug reports and feedback are always welcome.",
            style = T.small, color = c.muted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Developer.links.forEach { (label, url) -> Pill(label, { runCatching { uri.openUri(url) } }, icon = Ic.Chevron) }
        }
    }
}

/** The welcome screen's small credit: photo and name; tap to open Instagram. */
@Composable
fun MadeBy(modifier: Modifier = Modifier) {
    val c = P.colors
    val uri = LocalUriHandler.current
    Row(
        modifier.clip(CircleShape).cardClick("Open ${Developer.NAME}'s Instagram") { runCatching { uri.openUri(Developer.links.first().second) } },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeveloperPhoto(36.dp)
        Column {
            Text("Made by", style = T.small.copy(fontSize = 11.sp), color = c.muted)
            Text(Developer.NAME, style = T.title.copy(fontSize = 15.sp), color = c.text)
        }
    }
}
