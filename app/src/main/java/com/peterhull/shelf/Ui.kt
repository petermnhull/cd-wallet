package com.peterhull.shelf

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

private const val PageSize = 10
private const val MaxPages = 10
private val PagerHeight = 48.dp

@Composable
fun CdWalletApp(vm: AppViewModel, onLogin: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
            val demo by vm.demo.collectAsStateWithLifecycle()
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                if (loggedIn || demo) LibraryScreen(vm) else LoginScreen(vm, onLogin)
            }
        }
    }
}

@Composable
private fun ColumnScope.LoginScreen(vm: AppViewModel, onLogin: () -> Unit) {
    val status by vm.status.collectAsStateWithLifecycle()
    Column(
        Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("CD Wallet", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onLogin) { Text("Connect Spotify") }
        status?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ColumnScope.LibraryScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val items by vm.items.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val playerError by vm.player.error.collectAsStateWithLifecycle()
    val nowPlaying by vm.player.nowPlaying.collectAsStateWithLifecycle()
    val art by vm.player.art.collectAsStateWithLifecycle()
    val demo by vm.demo.collectAsStateWithLifecycle()
    val demoArt = remember { DemoData.nowPlayingArt() }
    var kind by rememberSaveable { mutableStateOf(Kind.Album) }
    var query by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable(query, kind) { mutableIntStateOf(0) }
    var artistUri by rememberSaveable { mutableStateOf<String?>(null) }
    var artistPage by rememberSaveable(artistUri) { mutableIntStateOf(0) }
    val artist = remember(items, artistUri) { items.firstOrNull { it.kind == Kind.Artist && it.uri == artistUri } }
    val artistAlbums = remember(items, artist) {
        artist?.let { a -> items.filter { it.kind == Kind.Album && a.uri in it.artistUris }.sortedByDescending { it.year } }
    }
    val current = if (demo) DemoData.nowPlaying else nowPlaying
    var showAlbum by rememberSaveable { mutableStateOf(false) }
    val album = rememberShownAlbum(vm, current, showAlbum)
    BackHandler(enabled = artist != null || album != null) {
        if (album != null) showAlbum = false else artistUri = null
    }

    KindTabs(
        kind,
        onSelect = { kind = it; artistUri = null; showAlbum = false },
        menu = { LibraryMenu(items, onSync = vm::sync, onLogout = vm::logout) },
    )
    Spacer(Modifier.height(8.dp))
    if (album != null) {
        AlbumHeader(album, onBack = { showAlbum = false })
    } else if (artist != null && artistAlbums != null) {
        ArtistHeader(artist, artistAlbums.size, onBack = { artistUri = null }, onPlay = { vm.playArtistSongs(context, artist) })
    } else OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Search ${kind.label.lowercase()}") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
    Spacer(Modifier.height(8.dp))

    val ofKind = remember(items, kind) {
        val all = items.filter { it.kind == kind && it.saved }
        // Albums file under their artist, like a shelf of CDs; everything else by title.
        if (kind == Kind.Album) all.sortedWith(compareBy({ normalize(it.subtitle) }, { it.normTitle }))
        else all.sortedBy { it.normTitle }
    }
    // With no query, browse the whole tab; a search keeps to its best matches.
    val results = remember(ofKind, query) { if (query.isBlank()) ofKind else search(ofKind, query, PageSize * MaxPages) }

    // Exactly PageSize rows always fill the space above the pager: there is deliberately no scrolling.
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        when {
            album != null -> AlbumTracks(album, current)
            artistAlbums != null -> if (artistAlbums.isEmpty()) {
                Text(
                    "No albums in your library",
                    Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                PagedList(artistAlbums.take(PageSize * MaxPages), artistPage, { artistPage = it }, subtitle = { it.year }) {
                    vm.play(context, it)
                }
            }
            ofKind.isEmpty() -> LibrarySummary(items, onSync = vm::sync, onLogout = vm::logout)
            results.isEmpty() -> Text(
                "No matches",
                Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> PagedList(results, page, { page = it }, subtitle = { it.subtitle }) { item ->
                focus.clearFocus()
                if (item.kind == Kind.Artist) artistUri = item.uri else vm.play(context, item)
            }
        }
    }

    (status ?: playerError)?.takeIf { !demo }?.let {
        Text(
            it,
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    current?.let {
        NowPlayingBar(it, if (demo) demoArt else art, vm.player, onClick = { showAlbum = !showAlbum; focus.clearFocus() })
    }
}

@Composable
private fun AlbumHeader(album: ShownAlbum, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(
                album.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private class ShownAlbum(val uri: String, val title: String, val artist: String, val tracks: List<AlbumTrack>?)

private fun AlbumTrack.isPlaying(np: NowPlaying?) =
    np != null && (uri == np.uri || normalize(title) == normalize(np.title))

/**
 * The album to show while the track list is open. It sticks while its songs keep playing, so a skip
 * that briefly reports no album, or a relinked copy from another release, doesn't make the list jump.
 */
@Composable
private fun rememberShownAlbum(vm: AppViewModel, nowPlaying: NowPlaying?, enabled: Boolean): ShownAlbum? {
    var shown by remember { mutableStateOf<ShownAlbum?>(null) }
    LaunchedEffect(enabled, nowPlaying?.albumUri, nowPlaying?.uri) {
        if (!enabled) {
            shown = null
            return@LaunchedEffect
        }
        val np = nowPlaying?.takeIf { it.albumUri.isNotEmpty() } ?: return@LaunchedEffect
        val s = shown
        // Only a loaded album sticks: a load cut short by a burst of track changes must be retried.
        if (s?.tracks != null && (s.uri == np.albumUri || s.tracks.any { it.isPlaying(np) })) return@LaunchedEffect
        if (s?.uri != np.albumUri) shown = ShownAlbum(np.albumUri, np.album, np.artist, null)
        shown = ShownAlbum(np.albumUri, np.album, np.artist, vm.albumTracks(np.albumUri) ?: emptyList())
    }
    if (!enabled) return null
    // Before the first load lands, show the header straight away rather than a frame of the library.
    return shown ?: nowPlaying?.takeIf { it.albumUri.isNotEmpty() }?.let { ShownAlbum(it.albumUri, it.album, it.artist, null) }
}

/** The playing album's songs, just to look at: the transport buttons stay the only controls. */
@Composable
private fun BoxWithConstraintsScope.AlbumTracks(album: ShownAlbum, nowPlaying: NowPlaying?) {
    val list = album.tracks
    when {
        list == null -> Unit
        list.isEmpty() -> Text(
            "Couldn't load this album's songs",
            Modifier.align(Alignment.Center),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> {
            // Fit the whole album on screen when it can; very long ones scroll.
            val rowHeight = (maxHeight / list.size).coerceIn(32.dp, 48.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                list.forEachIndexed { i, track ->
                    val playing = track.isPlaying(nowPlaying)
                    val color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${i + 1}",
                            Modifier.width(32.dp),
                            color = color,
                            textAlign = TextAlign.End,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            track.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (playing) color else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

/** Exactly PageSize rows fill the space above the pager, however tall it is. */
@Composable
private fun BoxWithConstraintsScope.PagedList(
    items: List<Item>,
    page: Int,
    onPage: (Int) -> Unit,
    subtitle: (Item) -> String,
    onClick: (Item) -> Unit,
) {
    val rowHeight = ((maxHeight - PagerHeight) / PageSize).coerceAtLeast(0.dp)
    val pages = (items.size + PageSize - 1) / PageSize
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f)) {
            items.drop(page * PageSize).take(PageSize).forEach { item ->
                ResultRow(item, subtitle(item), rowHeight) { onClick(item) }
            }
        }
        Pager(page, pages, onPage)
    }
}

@Composable
private fun ArtistHeader(artist: Item, albumCount: Int, onBack: () -> Unit, onPlay: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        AsyncImage(
            model = artist.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(
                if (albumCount == 1) "1 album" else "$albumCount albums",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Plays only the songs saved here, not the artist's whole catalogue.
        FilledTonalIconButton(onClick = onPlay) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Play ${artist.title}")
        }
    }
}

@Composable
private fun KindTabs(selected: Kind, onSelect: (Kind) -> Unit, menu: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Kind.entries.forEach { kind ->
            val isSelected = kind == selected
            Text(
                kind.label,
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    .clickable { onSelect(kind) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        menu()
    }
}

@Composable
private fun LibraryMenu(items: List<Item>, onSync: () -> Unit, onLogout: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val counts = remember(items) { items.filter { it.saved }.groupingBy { it.kind }.eachCount() }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "Library options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                Kind.entries.joinToString("\n") { "${counts[it] ?: 0} ${it.label.lowercase()}" },
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DropdownMenuItem(text = { Text("Sync library") }, onClick = { open = false; onSync() })
            DropdownMenuItem(text = { Text("Log out") }, onClick = { open = false; onLogout() })
        }
    }
}

@Composable
private fun LibrarySummary(items: List<Item>, onSync: () -> Unit, onLogout: () -> Unit) {
    val counts = remember(items) { items.filter { it.saved }.groupingBy { it.kind }.eachCount() }
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            Kind.entries.joinToString(" · ") { "${counts[it] ?: 0} ${it.label.lowercase()}" },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Row {
            TextButton(onClick = onSync) { Text("Sync library") }
            TextButton(onClick = onLogout) { Text("Log out") }
        }
    }
}

@Composable
private fun ResultRow(item: Item, subtitle: String, height: Dp, onClick: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size((height - 8.dp).coerceAtLeast(0.dp))
                .clip(if (item.kind == Kind.Artist) CircleShape else RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        if (height < 52.dp) {
            // Too short for two lines (e.g. keyboard open): put the subtitle on the same line.
            Text(
                buildAnnotatedString {
                    append(item.title)
                    if (subtitle.isNotEmpty()) withStyle(SpanStyle(color = dim)) { append("  $subtitle") }
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = dim,
                    )
                }
            }
        }
    }
}

@Composable
private fun Pager(page: Int, pages: Int, onPage: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(PagerHeight),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onPage(page - 1) }, enabled = page > 0) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous page")
        }
        Text(
            "${page + 1} / $pages",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = { onPage(page + 1) }, enabled = page < pages - 1) {
            Icon(Icons.Default.ChevronRight, contentDescription = "Next page")
        }
    }
}

@Composable
private fun NowPlayingBar(nowPlaying: NowPlaying, art: Bitmap?, player: Player, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val artModifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp))
        if (art != null) {
            Image(art.asImageBitmap(), contentDescription = null, modifier = artModifier, contentScale = ContentScale.Crop)
        } else {
            Box(artModifier.background(MaterialTheme.colorScheme.surface))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(nowPlaying.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                nowPlaying.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = player::previous) { Icon(Icons.Default.SkipPrevious, contentDescription = "Previous") }
        IconButton(onClick = player::togglePause) {
            if (nowPlaying.paused) Icon(Icons.Default.PlayArrow, contentDescription = "Play")
            else Icon(Icons.Default.Pause, contentDescription = "Pause")
        }
        IconButton(onClick = player::next) { Icon(Icons.Default.SkipNext, contentDescription = "Next") }
    }
}
