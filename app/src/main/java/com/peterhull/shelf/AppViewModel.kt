package com.peterhull.shelf

import android.app.Application
import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val auth = Auth(app)
    private val library = Library(app, auth)
    val player = Player.shared

    private val _demo = MutableStateFlow(false)
    val demo: StateFlow<Boolean> = _demo
    private val demoItems by lazy { DemoData.items(app) }

    val items: StateFlow<List<Item>> = combine(library.items, _demo) { real, demo -> if (demo) demoItems else real }
        .stateIn(viewModelScope, SharingStarted.Eagerly, library.items.value)
    private val _loggedIn = MutableStateFlow(auth.isLoggedIn)
    val loggedIn: StateFlow<Boolean> = _loggedIn
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    init {
        val stale = System.currentTimeMillis() - library.lastSyncMillis > 12 * 60 * 60 * 1000L
        if (auth.isLoggedIn && (items.value.isEmpty() || stale)) sync()
    }

    fun onRedirect(uri: Uri) = viewModelScope.launch {
        try {
            auth.handleRedirect(uri)
            _loggedIn.value = true
            sync()
        } catch (e: Exception) {
            _status.value = e.message
        }
    }

    fun sync() {
        if (_syncing.value) return
        val limitedUntil = library.rateLimitedUntil
        if (limitedUntil > System.currentTimeMillis()) {
            _status.value = rateLimitMessage(limitedUntil)
            return
        }
        _syncing.value = true
        viewModelScope.launch {
            _status.value = "Syncing…"
            try {
                library.sync { n -> _status.value = "Syncing… $n items" }
                _status.value = null
            } catch (e: RateLimited) {
                _status.value = rateLimitMessage(e.untilMillis)
            } catch (e: Exception) {
                _status.value = "Sync failed: ${e.message}"
                _loggedIn.value = auth.isLoggedIn
            } finally {
                _syncing.value = false
            }
        }
    }

    private fun rateLimitMessage(until: Long): String = "Sync paused by Spotify until ${formatTime(until)}"

    private fun formatTime(millis: Long): String {
        val day = if (DateUtils.isToday(millis)) 0 else DateUtils.FORMAT_SHOW_WEEKDAY
        return DateUtils.formatDateTime(getApplication(), millis, DateUtils.FORMAT_SHOW_TIME or day)
    }

    fun setDemo(on: Boolean) {
        _demo.value = on
    }

    fun play(context: Context, item: Item) {
        if (!_demo.value) player.play(context, item.uri)
    }

    /** Shuffled saved songs by this artist, rather than the artist's whole catalogue. */
    fun playArtistSongs(context: Context, artist: Item) {
        if (_demo.value) return
        val songs = items.value.filter { it.kind == Kind.Song && artist.uri in it.artistUris }
        player.playAll(context, songs.shuffled().map { it.uri })
    }

    /** The album's songs, or a short reason they couldn't be fetched (offline, rate limited, not an album). */
    suspend fun albumTracks(albumUri: String): Result<List<AlbumTrack>> {
        if (_demo.value) return Result.success(DemoData.nowPlayingAlbum)
        if (!albumUri.startsWith("spotify:album:")) {
            return Result.failure(IOException("Spotify didn't say which album this is ($albumUri)"))
        }
        return try {
            Result.success(library.albumTracks(albumUri))
        } catch (e: CancellationException) {
            throw e
        } catch (e: RateLimited) {
            Result.failure(IOException("Spotify asked CD Wallet to wait until ${formatTime(e.untilMillis)}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun logout() {
        auth.logout()
        library.clear()
        _loggedIn.value = false
    }
}
