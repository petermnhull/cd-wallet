package com.peterhull.shelf

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.Normalizer

enum class Kind(val label: String) { Song("Songs"), Album("Albums"), Artist("Artists"), Playlist("Playlists") }

data class Item(
    val uri: String,
    val kind: Kind,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val artistUris: List<String> = emptyList(),
    val year: String = "",
    // Albums that only contain saved songs are kept for artist pages but hidden from the Albums tab.
    val saved: Boolean = true,
) {
    val normTitle = normalize(title)
    val searchKey = normalize("$title $subtitle")

    fun toJson(): JSONObject = JSONObject()
        .put("uri", uri).put("kind", kind.name).put("title", title)
        .put("subtitle", subtitle).put("image", imageUrl ?: JSONObject.NULL)
        .put("artists", JSONArray(artistUris)).put("year", year).put("saved", saved)

    companion object {
        fun fromJson(o: JSONObject) = Item(
            uri = o.getString("uri"),
            kind = Kind.valueOf(o.getString("kind")),
            title = o.getString("title"),
            subtitle = o.getString("subtitle"),
            imageUrl = if (o.isNull("image")) null else o.getString("image"),
            artistUris = o.optJSONArray("artists")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            year = o.optString("year"),
            saved = o.optBoolean("saved", true),
        )
    }
}

fun normalize(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/** Every query word must appear somewhere; title-prefix matches rank first. */
fun search(items: List<Item>, query: String, limit: Int): List<Item> {
    val q = normalize(query).trim()
    if (q.isEmpty() || limit <= 0) return emptyList()
    val words = q.split(Regex("\\s+"))
    return items.asSequence()
        .filter { item -> words.all { it in item.searchKey } }
        .sortedWith(
            compareBy<Item>(
                { if (it.normTitle.startsWith(q)) 0 else if (q in it.normTitle) 1 else 2 },
                { it.kind.ordinal },
                { it.title.length },
            )
        )
        .take(limit)
        .toList()
}

data class AlbumTrack(val uri: String, val title: String)

/** Spotify asked us not to call it again before [untilMillis]. */
class RateLimited(val untilMillis: Long) : IOException("Spotify rate limit")

/** The user's saved songs, albums and playlists, cached on disk between launches. */
class Library(context: Context, private val auth: Auth) {
    private val file = File(context.filesDir, "library-v3.json")
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Item>> = _items
    private val trackCache = mutableMapOf<String, List<AlbumTrack>>()

    val lastSyncMillis: Long get() = if (file.exists()) file.lastModified() else 0

    /** When Spotify's last long rate limit ends, or 0 if there wasn't one. */
    val rateLimitedUntil: Long get() = prefs.getLong(KEY_LIMITED_UNTIL, 0)

    suspend fun sync(onProgress: (Int) -> Unit) {
        val out = mutableListOf<Item>()
        val artists = linkedMapOf<String, ArtistTally>()
        val albums = linkedMapOf<String, Item>()
        fetchPages("https://api.spotify.com/v1/me/playlists?limit=50") { p ->
            out += Item(p.getString("uri"), Kind.Playlist, p.getString("name"),
                p.optJSONObject("owner")?.optString("display_name").orEmpty(), pickImage(p.optJSONArray("images")))
            onProgress(out.size)
        }
        fetchPages("https://api.spotify.com/v1/me/albums?limit=50") { row ->
            val a = row.optJSONObject("album") ?: return@fetchPages
            albums[a.getString("uri")] = album(a, saved = true)
            tally(artists, a) { it.albums++ }
            onProgress(out.size)
        }
        fetchPages("https://api.spotify.com/v1/me/tracks?limit=50") { row ->
            val t = row.optJSONObject("track") ?: return@fetchPages
            if (t.optBoolean("is_local")) return@fetchPages
            out += Item(t.getString("uri"), Kind.Song, t.getString("name"), artists(t),
                pickImage(t.optJSONObject("album")?.optJSONArray("images")), artistUris = uris(t))
            tally(artists, t) { it.songs++ }
            // Link the song's album to everyone on the song, so features show up on their artist page too.
            t.optJSONObject("album")?.let { a ->
                val uri = a.optString("uri").ifEmpty { null } ?: return@let
                val existing = albums[uri] ?: album(a, saved = false)
                albums[uri] = existing.copy(artistUris = (existing.artistUris + uris(t)).distinct())
            }
            onProgress(out.size)
        }
        out += albums.values
        // Artists come from your saved songs and albums, so this stays within your library.
        val images = fetchArtistImages(artists.keys.map { it.substringAfterLast(':') })
        artists.forEach { (uri, tally) ->
            out += Item(uri, Kind.Artist, tally.name, tally.summary(), images[uri.substringAfterLast(':')])
        }
        _items.value = out
        save(out)
    }

    /** An album's track list, for showing what's coming up; fetched on demand and kept in memory. */
    suspend fun albumTracks(albumUri: String): List<AlbumTrack> {
        trackCache[albumUri]?.let { return it }
        if (rateLimitedUntil > System.currentTimeMillis()) throw RateLimited(rateLimitedUntil)
        val id = albumUri.substringAfterLast(':')
        val out = mutableListOf<AlbumTrack>()
        fetchPages("https://api.spotify.com/v1/albums/$id/tracks?limit=50&market=from_token") { t ->
            out += AlbumTrack(t.getString("uri"), t.getString("name"))
        }
        trackCache[albumUri] = out
        return out
    }

    fun clear() {
        file.delete()
        _items.value = emptyList()
    }

    private suspend fun fetchPages(firstUrl: String, onItem: (JSONObject) -> Unit) {
        var url: String? = firstUrl
        while (url != null) {
            val res = Http.get(url, auth.accessToken())
            if (res.code == 429) {
                waitOrGiveUp(res.retryAfterSeconds)
                continue
            }
            if (res.code != 200) throw IOException("Spotify returned ${res.code} for ${url.substringBefore('?')}")
            val page = JSONObject(res.body)
            val rows = page.getJSONArray("items")
            for (i in 0 until rows.length()) rows.optJSONObject(i)?.let(onItem)
            url = if (page.isNull("next")) null else page.getString("next")
        }
    }

    /**
     * Best-effort: artist art is optional, so a refused request just leaves artists without images.
     * Spotify only allows one artist per request now, so art from the last sync is reused.
     */
    private suspend fun fetchArtistImages(ids: List<String>): Map<String, String> {
        val known = _items.value.filter { it.kind == Kind.Artist }
            .mapNotNull { a -> a.imageUrl?.let { a.uri.substringAfterLast(':') to it } }.toMap()
        val out = mutableMapOf<String, String>()
        for (id in ids) {
            known[id]?.let { out[id] = it; continue }
            val url = "https://api.spotify.com/v1/artists/$id"
            var res = Http.get(url, auth.accessToken())
            while (res.code == 429) {
                // Artist art is optional, so a long wait keeps the rest of the sync rather than losing it.
                try {
                    waitOrGiveUp(res.retryAfterSeconds)
                } catch (e: RateLimited) {
                    return out
                }
                res = Http.get(url, auth.accessToken())
            }
            if (res.code == 404) continue
            if (res.code != 200) break
            pickImage(JSONObject(res.body).optJSONArray("images"))?.let { out[id] = it }
        }
        return out
    }

    /** Short waits are normal while paging; a long one (Spotify can ask for a day) ends the sync instead of hanging it. */
    private suspend fun waitOrGiveUp(retryAfterSeconds: Long?) {
        val seconds = retryAfterSeconds ?: 2
        if (seconds > MAX_WAIT_SECONDS) {
            val until = System.currentTimeMillis() + seconds * 1000
            prefs.edit().putLong(KEY_LIMITED_UNTIL, until).apply()
            throw RateLimited(until)
        }
        delay(seconds * 1000)
    }

    private fun load(): List<Item> = try {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { Item.fromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    private suspend fun save(items: List<Item>) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString())
    }

    private companion object {
        const val KEY_LIMITED_UNTIL = "rateLimitedUntil"
        const val MAX_WAIT_SECONDS = 60L

        fun artists(o: JSONObject): String {
            val arr = o.optJSONArray("artists") ?: return ""
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }.joinToString(", ")
        }

        fun uris(o: JSONObject): List<String> {
            val arr = o.optJSONArray("artists") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("uri")?.ifEmpty { null } }
        }

        fun album(a: JSONObject, saved: Boolean) = Item(
            a.getString("uri"), Kind.Album, a.getString("name"), artists(a), pickImage(a.optJSONArray("images")),
            artistUris = uris(a), year = a.optString("release_date").take(4), saved = saved,
        )

        fun tally(into: MutableMap<String, ArtistTally>, o: JSONObject, bump: (ArtistTally) -> Unit) {
            val arr = o.optJSONArray("artists") ?: return
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val uri = a.optString("uri").ifEmpty { null } ?: continue
                bump(into.getOrPut(uri) { ArtistTally(a.optString("name")) })
            }
        }

        /** Smallest image that still looks sharp at thumbnail size. */
        fun pickImage(images: JSONArray?): String? {
            if (images == null || images.length() == 0) return null
            val list = (0 until images.length()).mapNotNull { images.optJSONObject(it) }
            val best = list.filter { it.optInt("width", 0) in 100..400 }.minByOrNull { it.optInt("width") }
                ?: list.firstOrNull()
            return best?.optString("url")?.ifEmpty { null }
        }
    }
}

private class ArtistTally(val name: String) {
    var songs = 0
    var albums = 0

    fun summary() = listOfNotNull(count(songs, "song"), count(albums, "album")).joinToString(" · ")

    private fun count(n: Int, word: String) = if (n == 0) null else "$n $word" + if (n == 1) "" else "s"
}
