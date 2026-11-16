package com.peterhull.shelf

import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import androidx.media3.common.Player as Media3Player

/** Mirrors the Spotify app's playback into a Media3 player, and forwards controls back to it. */
@OptIn(UnstableApi::class)
class RemotePlayer(private val remote: Player, scope: CoroutineScope) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var artwork: ByteArray? = null

    init {
        scope.launch { remote.nowPlaying.collect { invalidateState() } }
        scope.launch {
            remote.art.collect { bitmap ->
                artwork = bitmap?.let(::toJpeg)
                invalidateState()
            }
        }
    }

    override fun getState(): State {
        val commands = Media3Player.Commands.Builder().addAll(
            Media3Player.COMMAND_PLAY_PAUSE,
            Media3Player.COMMAND_SEEK_TO_NEXT,
            Media3Player.COMMAND_SEEK_TO_PREVIOUS,
            Media3Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Media3Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Media3Player.COMMAND_GET_METADATA,
            Media3Player.COMMAND_GET_TIMELINE,
            Media3Player.COMMAND_RELEASE,
        ).build()
        val np = remote.nowPlaying.value
            ?: return State.Builder().setAvailableCommands(commands).setPlaybackState(Media3Player.STATE_IDLE).build()

        val metadata = MediaMetadata.Builder()
            .setTitle(np.title)
            .setArtist(np.artist)
            .apply { artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
            .build()
        // Spotify owns the real queue, so the playlist is just the current track between two
        // placeholders: that keeps the previous/next buttons enabled, and handleSeek forwards them.
        val playlist = listOf(
            MediaItemData.Builder("previous").build(),
            MediaItemData.Builder(np.uri).setMediaMetadata(metadata).setDurationUs(np.durationMs * 1000).build(),
            MediaItemData.Builder("next").build(),
        )
        val elapsed = if (np.paused) 0 else SystemClock.elapsedRealtime() - np.updatedAt
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(1)
            .setPlaybackState(Media3Player.STATE_READY)
            .setPlayWhenReady(!np.paused, Media3Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setContentPositionMs(
                PositionSupplier.getExtrapolating(np.positionMs + elapsed, if (np.paused) 0f else 1f)
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) remote.resume() else remote.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Media3Player.COMMAND_SEEK_TO_NEXT, Media3Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> remote.next()
            Media3Player.COMMAND_SEEK_TO_PREVIOUS, Media3Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> remote.previous()
            else -> remote.seekTo(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private fun toJpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
}
