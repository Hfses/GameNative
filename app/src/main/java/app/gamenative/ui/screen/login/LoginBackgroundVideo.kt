package app.gamenative.ui.screen.login

import android.graphics.Matrix
import android.net.Uri
import android.view.TextureView
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import timber.log.Timber
import kotlin.math.max

/**
 * A looping, user-supplied video played full-bleed behind the login screen.
 *
 * Scope on purpose: this is a login-only decorative background. It plays the [videoUri] the user
 * picked in Settings, honouring [soundOn]. It is lifecycle-aware (pauses when the app is
 * backgrounded, resumes on return) and releases the player on dispose, so it never leaks or keeps
 * decoding while a game is running — the login screen is never on top of a game.
 *
 * Failures are swallowed to a no-op: a missing/revoked file or an unsupported codec must never crash
 * or block login, it just shows nothing (the caller keeps its solid background behind this).
 */
@OptIn(UnstableApi::class)
@Composable
internal fun LoginBackgroundVideo(
    videoUri: String,
    soundOn: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Rebuild the player only when the source or audio choice actually changes.
    val exoPlayer = remember(videoUri, soundOn) {
        ExoPlayer.Builder(context).build().apply {
            volume = if (soundOn) 1f else 0f
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
            runCatching {
                setMediaItem(MediaItem.fromUri(Uri.parse(videoUri)))
                prepare()
            }.onFailure { Timber.w(it, "LoginBackgroundVideo: failed to prepare $videoUri") }
        }
    }

    // Last known video dimensions, used to center-crop (zoom-to-fill) the TextureView.
    val videoSize = remember(exoPlayer) { intArrayOf(0, 0) }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Bad codec / unreadable file — stop trying, leave the screen's solid bg visible.
                Timber.w(error, "LoginBackgroundVideo: playback error")
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

    // TextureView (not PlayerView's default SurfaceView) so the video composites inside the view
    // hierarchy and can sit behind translucent content; the update block re-binds a recreated player.
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
        modifier = modifier,
    )
}

/**
 * Center-crop (zoom-to-fill) transform for [tv] given source [videoW]x[videoH] — TextureView stretches
 * to its bounds by default; this rescales uniformly about the centre so the video keeps its aspect
 * ratio and covers the view (the shorter axis fills, the longer is cropped).
 */
private fun applyCenterCrop(tv: TextureView, videoW: Int, videoH: Int) {
    val viewW = tv.width
    val viewH = tv.height
    if (videoW <= 0 || videoH <= 0 || viewW <= 0 || viewH <= 0) return
    val scale = max(viewW.toFloat() / videoW, viewH.toFloat() / videoH)
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
