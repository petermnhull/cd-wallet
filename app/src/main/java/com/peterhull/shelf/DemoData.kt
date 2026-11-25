package com.peterhull.shelf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.SystemClock
import java.io.File

/**
 * Invented library with generated covers, for screenshots: no real artwork or personal data.
 * Turned on from a computer with `adb shell am start -n com.peterhull.shelf/.MainActivity --ez demo true`.
 */
object DemoData {
    private class Album(val title: String, val artist: String, val year: String)

    private val albums = listOf(
        Album("Low Tide", "The Harbour Lights", "2019"),
        Album("Northern Rooms", "Low Orchard", "2024"),
        Album("Quiet Machines", "Low Orchard", "2021"),
        Album("Field Recordings", "Low Orchard", "2018"),
        Album("Second Light", "Low Orchard", "2015"),
        Album("Copper Sky", "Low Orchard", "2012"),
        Album("Kindling", "Low Orchard", "2009"),
        Album("Slow Rivers", "Juniper Hale", "2022"),
        Album("Afterglow Hotel", "Paper Satellites", "2020"),
        Album("Below the Pines", "Mira Okafor", "2023"),
        Album("Yellow Kitchen", "Kofi Mensah Trio", "2017"),
        Album("Salt & Static", "The Harbour Lights", "2016"),
        Album("Blue Hour Radio", "Paper Satellites", "2014"),
        Album("The Lantern Year", "Juniper Hale", "2019"),
        Album("Small Hours", "Elsa Brandt", "2011"),
        Album("Glass House Sessions", "Glass Meridian", "2021"),
        Album("Open Windows", "Ada Lune", "2025"),
        Album("Morning Ferry", "Sol & the Tidewater", "2013"),
        Album("Distant Cities", "Glass Meridian", "2018"),
        Album("Soft Weather", "Ada Lune", "2022"),
        Album("Neon Garden", "Paper Satellites", "2010"),
        Album("Midnight Atlas", "Elsa Brandt", "2016"),
        Album("Harbour Songs", "Copperfield Choir", "2008"),
        Album("Waves & Wires", "Sol & the Tidewater", "2020"),
        Album("The Long Table", "Copperfield Choir", "2012"),
        Album("Lighthouse Keeper", "Mira Okafor", "2017"),
    )

    private val songs = listOf(
        "Paper Boats", "Undertow", "Room With a View", "Static Bloom", "Ferry Lights", "Ninth Floor",
        "Slow Dance in the Kitchen", "Winter Orchard", "Northbound", "Halfway Home", "Lanterns",
        "Salt Water", "Telephone Wires", "Small Talk", "Late Bus", "Cedar", "Afterimage", "Sundial",
        "Open Water", "Fog Lamps", "Echo Park", "Little Engines", "Morning Glass", "Driftwood",
        "Night Market", "Last Light", "Keepsake", "Quiet Hours", "Weathervane", "Long Shadows",
    )

    private val playlists = listOf("Sunday Morning", "Running", "Kitchen Dancing", "Focus", "Road Trip", "Rainy Days")

    val nowPlaying: NowPlaying
        get() = NowPlaying(
            uri = "demo:track:0", title = "Undertow", artist = "The Harbour Lights",
            albumUri = "demo:album:0", album = "Low Tide", paused = false,
            positionMs = 83_000, durationMs = 214_000, updatedAt = SystemClock.elapsedRealtime(),
        )

    /** Track list of the now-playing album ("Low Tide"). */
    val nowPlayingAlbum: List<AlbumTrack> = listOf(
        "Paper Boats", "Undertow", "Harbour Wall", "Salt Water", "Fog Lamps", "Tide Tables",
        "Driftwood", "Lighthouse", "Open Water", "Last Ferry Home", "Breakwater", "Shipping Forecast", "Low Water",
    ).mapIndexed { i, title -> AlbumTrack(if (title == "Undertow") "demo:track:0" else "demo:album0:$i", title) }

    /** Cover of the now-playing track's album ("Low Tide"). */
    fun nowPlayingArt(): Bitmap = cover(0)

    fun items(context: Context): List<Item> {
        val dir = File(context.cacheDir, "demo").apply { mkdirs() }
        fun art(name: String, seed: Int): String {
            val file = File(dir, "$name.png")
            if (!file.exists()) file.outputStream().use { cover(seed).compress(Bitmap.CompressFormat.PNG, 100, it) }
            return Uri.fromFile(file).toString()
        }
        val artistNames = albums.map { it.artist }.distinct()
        fun artistUri(name: String) = "demo:artist:${artistNames.indexOf(name)}"

        val out = mutableListOf<Item>()
        albums.forEachIndexed { i, a ->
            out += Item("demo:album:$i", Kind.Album, a.title, a.artist, art("album$i", i),
                artistUris = listOf(artistUri(a.artist)), year = a.year)
        }
        songs.forEachIndexed { i, title ->
            val a = albums[i % albums.size]
            out += Item("demo:track:$i", Kind.Song, title, a.artist, art("album${i % albums.size}", i % albums.size),
                artistUris = listOf(artistUri(a.artist)))
        }
        artistNames.forEachIndexed { i, name ->
            val songCount = songs.indices.count { albums[it % albums.size].artist == name }
            val albumCount = albums.count { it.artist == name }
            val summary = listOf(songCount to "song", albumCount to "album")
                .filter { it.first > 0 }
                .joinToString(" · ") { (n, w) -> "$n $w" + if (n == 1) "" else "s" }
            out += Item(artistUri(name), Kind.Artist, name, summary, art("artist$i", 100 + i))
        }
        playlists.forEachIndexed { i, title ->
            out += Item("demo:playlist:$i", Kind.Playlist, title, "Alex", art("playlist$i", 200 + i))
        }
        return out
    }

    private val palettes = listOf(
        0xFF1F7A75 to 0xFFDFF5F3, 0xFFB34700 to 0xFFFFE8D6, 0xFF2D3A8C to 0xFFE6E9FF,
        0xFF6B2D5C to 0xFFF7E1EF, 0xFF1B1F1F to 0xFFE6ECEA, 0xFF7A6A1F to 0xFFF6F0D3,
        0xFF0F5132 to 0xFFD9F2E6, 0xFF8C2D2D to 0xFFFBE3E3,
    )

    /** A simple two-colour geometric cover, varied by seed. */
    private fun cover(seed: Int, size: Int = 300): Bitmap {
        val (dark, light) = palettes[seed % palettes.size]
        val inverted = (seed / palettes.size) % 2 == 1
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (if (inverted) light else dark).toInt() }
        canvas.drawColor((if (inverted) dark else light).toInt())
        val s = size.toFloat()
        when (seed % 5) {
            0 -> canvas.drawCircle(s * 0.5f, s * 0.58f, s * 0.28f, paint)
            1 -> for (i in 0 until 6) canvas.drawRect(0f, s * i / 6f, s, s * i / 6f + s / 12f, paint)
            2 -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.035f
                for (i in 1..5) canvas.drawCircle(s * 0.5f, s * 0.5f, s * 0.08f * i, paint)
            }
            3 -> canvas.drawPath(Path().apply { moveTo(0f, s); lineTo(s, s * 0.25f); lineTo(s, s); close() }, paint)
            else -> for (x in 0 until 5) for (y in 0 until 5) {
                canvas.drawCircle(s * (x + 0.5f) / 5, s * (y + 0.5f) / 5, s * 0.05f, paint)
            }
        }
        return bitmap
    }
}
