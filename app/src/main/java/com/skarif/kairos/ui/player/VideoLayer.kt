package com.skarif.kairos.ui.player

import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.ImageBitmap
import com.skarif.kairos.ui.components.blurredTiny
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skarif.kairos.ui.LyricsState
import com.skarif.kairos.ui.MainViewModel
import com.skarif.kairos.ui.components.CircleButton
import com.skarif.kairos.ui.components.Ic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The video, drawn inside the cover. The cover's own picture stays underneath until the first
 * frame is out, so switching never flashes black.
 *
 * A TextureView, not a SurfaceView: the cover is clipped to rounded corners, slid sideways when
 * swiping between songs and moved with the sheet, and a SurfaceView ignores all of that.
 *
 * [fill] crops the picture to fill the cover; otherwise it is fitted whole (a 16:9 video in this
 * tall cover would lose half its width, and faces with it). A tiny, blurred live frame fills the
 * background behind the TextureView; no per-frame video processing is used.
 */
@Composable
fun VideoLayer(vm: MainViewModel, fill: Boolean, showSubtitles: Boolean = true, onToggleFill: () -> Unit) {
    val size by vm.videoSize.collectAsStateWithLifecycle()
    val firstFrame by vm.videoFirstFrame.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val active = now.videoActive
    // Lyrics as subtitles: only with synced lyrics (a plain text has no timing to follow).
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lyricsState by vm.lyrics.collectAsStateWithLifecycle()
    val lyricsOffset by vm.lyricsOffset.collectAsStateWithLifecycle()
    val syncedLyrics = (lyricsState as? LyricsState.Ready)?.lyrics?.takeIf { it.synced && !it.isEmpty() }

    // If the player never reports a first frame, show the video anyway after a moment.
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        waited = false
        if (active) { delay(2_500); waited = true }
    }
    // A rendered frame is definitive evidence that the video surface has picture content.
    // Do not gate it on now.videoActive: during replaceMediaItem/seek, the controller can
    // briefly publish the old audio item's identity even while the video decoder is rendering.
    // The first-frame flag is reset by swapStream(), so it cannot reveal a stale previous frame.
    val revealVideo = firstFrame || (active && waited)
    val fade by animateFloatAsState(if (revealVideo) 1f else 0f, tween(250), label = "videoFade")


    // The screen stays on while the picture moves, and no longer.
    val view = LocalView.current
    DisposableEffect(now.isPlaying && active) {
        view.keepScreenOn = now.isPlaying && active
        onDispose { view.keepScreenOn = false }
    }

    val context = LocalContext.current
    val texture = remember { TextureView(context) }
    DisposableEffect(texture) {
        vm.attachVideo(texture)
        onDispose { vm.detachVideo(texture) }
    }

    // A soft live-video blur behind a *fitted* picture. A tiny frame is read from the TextureView
    // about once a second (TextureView.getBitmap at 72 px is cheap) and blurred off the UI thread.
    // (An earlier version used PixelCopy on a Surface wrapped around the TextureView's own
    // SurfaceTexture. That is not a documented source for PixelCopy and most likely returned
    // nothing every time, so the blur never appeared; getBitmap is the supported way.)
    var blurredFrame by remember(now.songId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(active, firstFrame, now.isPlaying, now.songId, texture, fill) {
        if (!active || !firstFrame) {
            if (!active) blurredFrame = null
            return@LaunchedEffect
        }
        // With Fill the picture covers the whole cover, so the blur would never be seen.
        if (fill) return@LaunchedEffect
        // One 72 px bitmap reused for every read: getBitmap runs on the UI thread, so it should not
        // also allocate each time. It is only read again after the blur has finished with it.
        val buffer = android.graphics.Bitmap.createBitmap(72, 72, android.graphics.Bitmap.Config.ARGB_8888)
        do {
            if (texture.isAvailable && texture.width > 0 && texture.height > 0) {
                val frame = runCatching { texture.getBitmap(buffer) }.getOrNull()
                if (frame != null) {
                    val blurred = try {
                        withContext(Dispatchers.Default) { blurredTiny(frame, side = 72, radius = 2) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (blurred != null) blurredFrame = blurred
                }
            }
            if (!now.isPlaying) break
            // The glow behind a fitted picture drifts slowly; every 2.5 s is enough (it was every 1.2 s,
            // each one a synchronous read-back of the video texture on the UI thread).
            // getBitmap copies the frame from the GPU on the UI thread, which can hitch weaker phones,
            // so it runs rarely: the glow only needs to follow the picture's overall colour.
            delay(8_000)
        } while (true)
    }

    val ratio = if (size.width > 0 && size.height > 0 && size.pixelWidthHeightRatio.isFinite() && size.pixelWidthHeightRatio > 0f) {
        (size.width * size.pixelWidthHeightRatio / size.height).coerceIn(0.25f, 4f)
    } else 16f / 9f
    BoxWithConstraints(
        Modifier.fillMaxSize().clipToBounds().background(Color(0xFF050609)),
        contentAlignment = Alignment.Center,
    ) {
        // The blurred frame is a soft, continuously refreshed extension of the actual video,
        // not a generated colour glow. It remains visible around a fitted 16:9 frame.
        val currentBlurredFrame = blurredFrame
        if (currentBlurredFrame != null) {
            Image(
                bitmap = currentBlurredFrame,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    alpha = if (revealVideo) 0.88f else 0f
                    scaleX = 1.12f
                    scaleY = 1.12f
                    // Blur is already baked into the tiny bitmap; scaling softens it further.
                },
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)))
        }
        val boxRatio = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 1f
        // Fit keeps the whole frame inside the cover. Fill deliberately makes one dimension
        // larger than the cover so the parent clips the excess (centre-crop).
        val width: androidx.compose.ui.unit.Dp
        val height: androidx.compose.ui.unit.Dp
        if (fill) {
            if (boxRatio >= ratio) {
                width = maxWidth
                height = maxWidth / ratio
            } else {
                width = maxHeight * ratio
                height = maxHeight
            }
        } else {
            if (boxRatio < ratio) {
                width = maxWidth
                height = maxWidth / ratio
            } else {
                width = maxHeight * ratio
                height = maxHeight
            }
        }
        AndroidView(
            factory = { texture },
            modifier = Modifier.requiredSize(width, height).graphicsLayer {
                alpha = fade
            },
        )
        // Subtitles: the current lyric line over the lower part of the picture, on a soft scrim.
        // Only on the full player: this layer is also the mini player's tiny cover, which has no room for overlays.
        if (showSubtitles && syncedLyrics != null && settings.videoSubtitles && revealVideo) {
            VideoSubtitle(vm, syncedLyrics, lyricsOffset, Modifier.align(Alignment.BottomCenter))
        }
        // The fit/fill and subtitles buttons live in NowPlayingPane, above the cover's gesture layer:
        // this layer is drawn underneath it, so buttons placed here never received a touch.
    }
}

/**
 * The current lyric line as a subtitle. It follows [rememberLivePosition], which is real Compose state.
 *
 * The old code read `vm.position.value` inside `derivedStateOf`. `position` is a StateFlow, which
 * Compose cannot observe, so the derived line was computed once and never again: the subtitle froze
 * on whatever line was current when the video opened. Here the line only recomposes when the line
 * itself changes, not on every position tick.
 */
@Composable
private fun VideoSubtitle(vm: MainViewModel, lyrics: com.skarif.kairos.lyrics.Lyrics, offsetMs: Long, modifier: Modifier) {
    val position = com.skarif.kairos.ui.screens.rememberLivePosition(vm)
    val line by remember(lyrics, offsetMs) {
        derivedStateOf {
            // Same rule as the lyrics page: a positive offset shows lyrics later.
            val i = lyrics.indexAt(position.value - offsetMs)
            if (i >= 0) lyrics.lines[i].text.trim() else ""
        }
    }
    if (line.isNotEmpty()) {
        androidx.compose.material3.Text(
            line,
            color = Color.White,
            style = com.skarif.kairos.ui.theme.T.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 3,
            modifier = modifier
                // Bottom 46 dp clears the download and heart buttons that overlap the cover's lower edge.
                .padding(start = 64.dp, end = 64.dp, bottom = 46.dp)
                .background(Color.Black.copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
