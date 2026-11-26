package com.skarif.kairos.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.skarif.kairos.ui.theme.P
import com.skarif.kairos.ui.theme.T

/** Something Kairos is built with or relies on, and its licence. */
class Credit(val name: String, val by: String, val licence: String, val url: String, val onlineOnly: Boolean = false)

/** Everything Kairos uses, credited. Kept in step with gradle/libs.versions.toml and README's Credits. */
val CREDITS = listOf(
    Credit("Kotlin and kotlinx.coroutines", "JetBrains", "Apache 2.0", "https://kotlinlang.org"),
    Credit("Jetpack Compose and Material 3", "Google, Android Open Source Project", "Apache 2.0", "https://developer.android.com/jetpack/compose"),
    Credit("AndroidX Core, Activity, Lifecycle, Navigation", "Google, Android Open Source Project", "Apache 2.0", "https://developer.android.com/jetpack/androidx"),
    Credit("Media3: ExoPlayer and MediaSession", "Google, Android Open Source Project", "Apache 2.0", "https://developer.android.com/media/media3"),
    Credit("Room, DataStore and Palette", "Google, Android Open Source Project", "Apache 2.0", "https://developer.android.com/jetpack/androidx"),
    Credit("Guava", "Google", "Apache 2.0", "https://github.com/google/guava"),
    Credit("Urbanist font", "Corey Hu", "SIL Open Font License 1.1", "https://github.com/coreyhu/Urbanist"),
    Credit("LRCLIB lyrics", "LRCLIB, free lyrics database", "Free public API", "https://lrclib.net"),
    Credit("NewPipe Extractor", "Team NewPipe", "GPL 3.0", "https://github.com/TeamNewPipe/NewPipeExtractor", onlineOnly = true),
    Credit("OkHttp and Okio", "Square", "Apache 2.0", "https://square.github.io/okhttp/", onlineOnly = true),
    Credit("Rhino JavaScript engine", "Mozilla", "Mozilla Public License 2.0", "https://github.com/mozilla/rhino", onlineOnly = true),
    Credit("jsoup", "Jonathan Hedley", "MIT", "https://jsoup.org", onlineOnly = true),
    Credit("nanojson", "Matt Mastracci", "MIT / Apache 2.0", "https://github.com/mmastrac/nanojson", onlineOnly = true),
    Credit("Protocol Buffers Lite", "Google", "BSD 3-Clause", "https://protobuf.dev", onlineOnly = true),
    Credit("Java library desugaring", "Google", "GPL 2.0 with Classpath Exception", "https://github.com/google/desugar_jdk_libs", onlineOnly = true),
    Credit("YouTube Music and YouTube", "Google. Kairos is not affiliated with them", "Content source", "https://music.youtube.com", onlineOnly = true),
)

/** Settings: who made what Kairos is built on. Folded to a few lines until opened. */
@Composable
fun CreditsCard(online: Boolean) {
    val c = P.colors
    val uri = LocalUriHandler.current
    var open by remember { mutableStateOf(false) }
    val list = CREDITS.filter { online || !it.onlineOnly }
    Card(Modifier) {
        Text("Credits", style = T.h2, color = c.text)
        Text(
            "Kairos stands on open-source work by these people and projects. Tap one to visit it.",
            style = T.small, color = c.muted,
        )
        (if (open) list else list.take(4)).forEach { cr ->
            Row(
                Modifier.fillMaxWidth().cardClick("Open ${cr.name}") { runCatching { uri.openUri(cr.url) } }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(cr.name, style = T.body, color = c.text)
                    Text("${cr.by} · ${cr.licence}", style = T.small, color = c.muted)
                }
                Icon(Ic.Chevron, null, tint = c.muted, modifier = Modifier.size(16.dp))
            }
        }
        if (list.size > 4) Pill(if (open) "Show less" else "Show all ${list.size}", { open = !open })
    }
}
