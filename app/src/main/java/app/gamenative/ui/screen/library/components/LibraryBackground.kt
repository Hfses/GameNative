package app.gamenative.ui.screen.library.components

import android.graphics.Matrix
import android.net.Uri
import android.view.TextureView
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import timber.log.Timber
import kotlin.math.max

/**
 * A full-bleed wallpaper behind the library — a looping user-picked video (optionally with sound)
 * or a static image. Video takes priority when both are set. Lifecycle-aware (pauses when the app
 * is backgrounded, releases the player on dispose) and swallows playback errors to a no-op so a bad
 * file never crashes the library — the caller keeps its solid background beneath this.
 *
 * The caller is responsible for drawing a scrim over this for text legibility.
 */
@OptIn(UnstableApi::class)
@Composable
fun LibraryBackground(
    videoUri: String,
    imageUri: String,
    soundOn: Boolean,
    modifier: Modifier = Modifier,
) {
    if (videoUri.isNotBlank()) {
        LibraryVideoBackground(videoUri = videoUri, soundOn = soundOn, modifier = modifier)
    } else if (imageUri.isNotBlank()) {
        CoilImage(
            imageModel = { imageUri },
            imageOptions = ImageOptions(contentDescription = null, contentScale = ContentScale.Crop),
            modifier = modifier,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun LibraryVideoBackground(
    videoUri: String,
    soundOn: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val exoPlayer = remember(videoUri, soundOn) {
        ExoPlayer.Builder(context).build().apply {
            volume = if (soundOn) 1f else 0f
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
            runCatching {
                setMediaItem(MediaItem.fromUri(Uri.parse(videoUri)))
                prepare()
            }.onFailure { Timber.w(it, "LibraryBackground: failed to prepare $videoUri") }
        }
    }

    // Last known video dimensions, used to center-crop (zoom-to-fill) the TextureView so the
    // wallpaper covers the screen without letterboxing — mirrors the old RESIZE_MODE_ZOOM.
    val videoSize = remember(exoPlayer) { intArrayOf(0, 0) }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Timber.w(error, "LibraryBackground: playback error")
                exoPlayer.stop()
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                videoSize[0] = size.width
                videoSize[1] = size.height
            }
        }
        exoPlayer.addListener(listener)

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> exoPlayer.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            exoPlayer.removeListener(listener)
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    // A TextureView (not PlayerView's default SurfaceView) so the video composites INSIDE the view
    // hierarchy, respecting z-order/alpha — it shows correctly behind the translucent library chrome
    // instead of only through a fully-transparent window. The update block re-binds the (possibly
    // recreated) ExoPlayer, avoiding a black/frozen view when videoUri/soundOn change.
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    applyCenterCrop(v as TextureView, videoSize[0], videoSize[1])
                }
            }
        },
        update = { tv ->
            exoPlayer.setVideoTextureView(tv)
            applyCenterCrop(tv, videoSize[0], videoSize[1])
        },
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * Applies a center-crop (zoom-to-fill) transform to [tv] given the source [videoW]x[videoH].
 * TextureView stretches its content to the full view bounds by default; this rescales uniformly so
 * the shorter axis fills and the longer axis is cropped, keeping the video's aspect ratio.
 */
private fun applyCenterCrop(tv: TextureView, videoW: Int, videoH: Int) {
    val viewW = tv.width
    val viewH = tv.height
    if (videoW <= 0 || videoH <= 0 || viewW <= 0 || viewH <= 0) return
    val scale = max(viewW.toFloat() / videoW, viewH.toFloat() / videoH)
    // The default fill already maps the video to viewW x viewH (non-uniform); this matrix converts
    // that back to a uniform cover-scale about the view centre.
    val matrix = Matrix().apply {
        setScale(
            videoW * scale / viewW,
            videoH * scale / viewH,
            viewW / 2f,
            viewH / 2f,
        )
    }
    tv.setTransform(matrix)
}
