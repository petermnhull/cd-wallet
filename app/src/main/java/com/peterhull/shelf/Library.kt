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

/** The user's saved songs, albums and playlists, cached on disk between launches. */
class Library(context: Context, private val auth: Auth) {
    private val file = File(context.filesDir, "library-v3.json")
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Item>> = _items

    val lastSyncMillis: Long get() = if (file.exists()) file.lastModified() else 0

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

    fun clear() {
        file.delete()
        _items.value = emptyList()
    }

    private suspend fun fetchPages(firstUrl: String, onItem: (JSONObject) -> Unit) {
        var url: String? = firstUrl
        while (url != null) {
            val res = Http.get(url, auth.accessToken())
            if (res.code == 429) {
                delay((res.retryAfterSeconds ?: 2) * 1000)
                continue
            }
            if (res.code != 200) throw IOException("Spotify returned ${res.code} for ${url.substringBefore('?')}")
            val page = JSONObject(res.body)
            val rows = page.getJSONArray("items")
            for (i in 0 until rows.length()) rows.optJSONObject(i)?.let(onItem)
            url = if (page.isNull("next")) null else page.getString("next")
        }
    }

    /** Best-effort: artist art is optional, so a refused request just leaves artists without images. */
    private suspend fun fetchArtistImages(ids: List<String>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (chunk in ids.chunked(50)) {
            val url = "https://api.spotify.com/v1/artists?ids=${chunk.joinToString(",")}"
            var res = Http.get(url, auth.accessToken())
            while (res.code == 429) {
                delay((res.retryAfterSeconds ?: 2) * 1000)
                res = Http.get(url, auth.accessToken())
            }
            if (res.code != 200) break
            val arr = JSONObject(res.body).optJSONArray("artists") ?: continue
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                pickImage(a.optJSONArray("images"))?.let { out[a.getString("id")] = it }
            }
        }
        return out
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
