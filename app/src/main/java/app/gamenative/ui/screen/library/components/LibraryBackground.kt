package app.gamenative.ui.screen.library.components

import android.net.Uri
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import timber.log.Timber

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

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Timber.w(error, "LibraryBackground: playback error")
                exoPlayer.stop()
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

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        modifier = modifier.fillMaxSize(),
    )
}
