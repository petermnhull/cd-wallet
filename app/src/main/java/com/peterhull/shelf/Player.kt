package com.peterhull.shelf

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NowPlaying(
    val uri: String,
    val title: String,
    val artist: String,
    val albumUri: String,
    val album: String,
    val paused: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** elapsedRealtime when positionMs was reported. */
    val updatedAt: Long,
)

/** Controls playback through the installed Spotify app (it does the actual streaming). */
class Player {
    companion object {
        /** One connection shared by the UI and the media session service. */
        val shared = Player()
    }

    private var remote: SpotifyAppRemote? = null
    private var connecting = false
    private var pendingUris: List<String> = emptyList()
    private var playGeneration = 0
    private var artUri: String? = null

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying
    private val _art = MutableStateFlow<Bitmap?>(null)
    val art: StateFlow<Bitmap?> = _art
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun connect(context: Context) {
        if (remote?.isConnected == true || connecting) return
        connecting = true
        val params = ConnectionParams.Builder(Config.CLIENT_ID)
            .setRedirectUri(Config.REDIRECT_URI)
            .showAuthView(true)
            .build()
        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                connecting = false
                remote = appRemote
                _error.value = null
                appRemote.playerApi.subscribeToPlayerState().setEventCallback(::onState)
                if (pendingUris.isNotEmpty()) {
                    playList(appRemote, pendingUris)
                    pendingUris = emptyList()
                }
            }

            override fun onFailure(error: Throwable) {
                connecting = false
                remote = null
                Log.w("CDWallet", "App Remote connection failed", error)
                _error.value = when (error) {
                    is CouldNotFindSpotifyApp -> "Install the Spotify app (it plays the audio in the background)."
                    is NotLoggedInException -> "Open the Spotify app once and log in."
                    is UserNotAuthorizedException ->
                        "Spotify refused the connection: ${error.message}"
                    else -> "Couldn't connect to Spotify: ${error.message}"
                }
            }
        })
    }

    fun disconnect() {
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
    }

    fun play(context: Context, uri: String) = playAll(context, listOf(uri))

    /** Plays the first URI and queues the rest, in order. */
    fun playAll(context: Context, uris: List<String>) {
        if (uris.isEmpty()) return
        val r = remote
        if (r?.isConnected == true) {
            playList(r, uris)
        } else {
            pendingUris = uris
            connect(context)
        }
    }

    private fun playList(r: SpotifyAppRemote, uris: List<String>) {
        // Each queue call waits for the previous one so the order holds; a newer play cancels the chain.
        val generation = ++playGeneration
        fun queueFrom(i: Int) {
            if (i >= uris.size || generation != playGeneration) return
            r.playerApi.queue(uris[i]).setResultCallback { queueFrom(i + 1) }
        }
        r.playerApi.play(uris.first()).setResultCallback { queueFrom(1) }
    }

    fun togglePause() {
        val api = remote?.playerApi ?: return
        if (_nowPlaying.value?.paused == true) api.resume() else api.pause()
    }

    fun resume() {
        remote?.playerApi?.resume()
    }

    fun pause() {
        remote?.playerApi?.pause()
    }

    fun seekTo(positionMs: Long) {
        remote?.playerApi?.seekTo(positionMs)
    }

    fun next() {
        remote?.playerApi?.skipNext()
    }

    fun previous() {
        remote?.playerApi?.skipPrevious()
    }

    private fun onState(state: PlayerState) {
        val track = state.track ?: return
        _nowPlaying.value = NowPlaying(
            uri = track.uri.orEmpty(),
            title = track.name.orEmpty(),
            artist = track.artist?.name.orEmpty(),
            albumUri = track.album?.uri.orEmpty(),
            album = track.album?.name.orEmpty(),
            paused = state.isPaused,
            positionMs = state.playbackPosition,
            durationMs = track.duration,
            updatedAt = SystemClock.elapsedRealtime(),
        )
        val imageUri = track.imageUri
        if (imageUri?.raw != artUri) {
            artUri = imageUri?.raw
            _art.value = null
            if (imageUri != null) {
                remote?.imagesApi?.getImage(imageUri, Image.Dimension.SMALL)?.setResultCallback { _art.value = it }
            }
        }
    }
}
