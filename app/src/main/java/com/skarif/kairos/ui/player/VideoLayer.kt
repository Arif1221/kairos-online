package com.skarif.kairos.ui.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.Surface
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
import com.skarif.kairos.ui.MainViewModel
import com.skarif.kairos.ui.components.CircleButton
import com.skarif.kairos.ui.components.Ic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

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
fun VideoLayer(vm: MainViewModel, fill: Boolean, onToggleFill: () -> Unit) {
    val size by vm.videoSize.collectAsStateWithLifecycle()
    val firstFrame by vm.videoFirstFrame.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val active = now.videoActive

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

    LaunchedEffect(active, firstFrame, waited, fade > 0.5f, size.width, size.height) {
        android.util.Log.i("KairosVideo", "UI layer: active=$active firstFrame=$firstFrame waited=$waited reveal=$revealVideo visible=${fade > 0.5f} size=${size.width}x${size.height} fill=$fill")
    }

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

    // Keep a true live-video blur behind the player surface. We sample a tiny frame at a
    // deliberately low rate via PixelCopy (off the UI thread), blur it off the UI thread, and
    // reuse that bitmap between samples. Reset when inactive or on song change so no stale frame lingers.
    var blurredFrame by remember(now.songId) { mutableStateOf<ImageBitmap?>(null) }
    val copyThread = remember { HandlerThread("KairosVideoBlurCopy").apply { start() } }
    val copyHandler = remember(copyThread) { Handler(copyThread.looper) }
    DisposableEffect(copyThread) {
        onDispose { copyThread.quitSafely() }
    }
    LaunchedEffect(active, firstFrame, now.isPlaying, now.songId, texture) {
        if (!active || !firstFrame) {
            if (!active) blurredFrame = null
            return@LaunchedEffect
        }
        do {
            if (texture.isAvailable && texture.width > 0 && texture.height > 0) {
                val frame = captureTinyFrame(texture, copyHandler)
                if (frame != null) {
                    val blurred = try {
                        withContext(Dispatchers.Default) { blurredTiny(frame, side = 72, radius = 2) }
                    } catch (_: Exception) {
                        null
                    } finally {
                        frame.recycle()
                    }
                    if (blurred != null) blurredFrame = blurred
                }
            }
            if (!now.isPlaying) break
            delay(850)
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
        // Explicit, discoverable framing control. Double-tap remains supported as a shortcut.
        CircleButton(
            icon = if (fill) Ic.FillFrame else Ic.FitFrame,
            contentDescription = if (fill) "Video fills cover; switch to fit" else "Video fits cover; switch to fill",
            onClick = onToggleFill,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).zIndex(2f),
            size = 40.dp,
            container = Color(0x80000000),
            content = Color.White,
            iconSize = 19.dp,
        )
    }
}

/**
 * Captures a 72x72 frame off the UI thread via [PixelCopy] when the [TextureView] surface is ready,
 * falling back to [TextureView.getBitmap] only if no SurfaceTexture is attached yet.
 */
private suspend fun captureTinyFrame(texture: TextureView, handler: Handler): Bitmap? {
    val st = texture.surfaceTexture ?: return runCatching { texture.getBitmap(72, 72) }.getOrNull()
    val surface = Surface(st)
    val bmp = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
    return suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
            runCatching { surface.release() }
            if (!bmp.isRecycled) bmp.recycle()
        }
        try {
            PixelCopy.request(
                surface,
                bmp,
                { result ->
                    surface.release()
                    if (result == PixelCopy.SUCCESS && cont.isActive) {
                        cont.resume(bmp)
                    } else {
                        bmp.recycle()
                        if (cont.isActive) cont.resume(null)
                    }
                },
                handler,
            )
        } catch (_: Exception) {
            surface.release()
            bmp.recycle()
            if (cont.isActive) cont.resume(null)
        }
    }
}
