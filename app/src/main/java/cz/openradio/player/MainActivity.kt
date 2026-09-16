package cz.openradio.player

import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private var controller: MediaController? by mutableStateOf(null)
    private var localAudioSelection by mutableStateOf<List<android.net.Uri>>(emptyList())

    private val localAudioPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val existing = localAudioSelection.map { it.toString() }.toSet()
        val merged = localAudioSelection + uris.filterNot { it.toString() in existing }
        merged.forEach { uri ->
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
        }
        localAudioSelection = merged
        getSharedPreferences("local_music", MODE_PRIVATE).edit()
            .putString("uris", merged.joinToString("\n") { it.toString() }).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        localAudioSelection = getSharedPreferences("local_music", MODE_PRIVATE).getString("uris", "")
            ?.split('\n')?.filter { it.isNotBlank() }?.map { android.net.Uri.parse(it) } ?: emptyList()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            controller = future.get()
            if (getSharedPreferences("playback_settings", MODE_PRIVATE).getBoolean("auto_start", false)) {
                val prefs = getSharedPreferences("last_radio", MODE_PRIVATE)
                val id = prefs.getString("id", null)
                val url = prefs.getString("url", null)
                val name = prefs.getString("name", null)
                val logo = prefs.getString("logo", null)
                if (id != null && url != null && name != null) {
                    val item = MediaItem.Builder().setMediaId(id).setUri(url).setMediaMetadata(
                        MediaMetadata.Builder().setTitle(name).setArtist("Internet Radio")
                            .setArtworkUri(logo?.let { android.net.Uri.parse(it) }).build()
                    ).build()
                    controller?.setMediaItem(item)
                    controller?.prepare()
                    controller?.play()
                }
            }
        }, mainExecutor)
        setContent {
            MaterialTheme {
                RadioPlayerApp(controller, localAudioSelection) { localAudioPicker.launch(arrayOf("audio/*")) }
            }
        }
    }

    override fun onDestroy() {
        controller?.release()
        controller = null
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadioPlayerApp(controller: MediaController?, localAudioUris: List<android.net.Uri>, onPickLocalAudio: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { StationRepository(AppDatabase.getInstance(context).stationDao()) }
    val preferences = remember { context.getSharedPreferences("playback_settings", android.content.Context.MODE_PRIVATE) }
    var selectedTab by remember { mutableStateOf(0) }
    var showFullPlayer by remember { mutableStateOf(false) }
    var isPlaying by remember(controller) { mutableStateOf(controller?.isPlaying == true) }
    var playbackState by remember(controller) { mutableStateOf(controller?.playbackState ?: Player.STATE_IDLE) }
    var currentName by remember(controller) { mutableStateOf(controller?.currentMediaItem?.mediaMetadata?.title?.toString()) }
    var currentArtist by remember(controller) { mutableStateOf(controller?.currentMediaItem?.mediaMetadata?.artist?.toString() ?: "") }
    var currentArtwork by remember(controller) { mutableStateOf(controller?.currentMediaItem?.mediaMetadata?.artworkUri) }

    DisposableEffect(controller) {
        if (controller == null) onDispose { } else {
            fun sync() {
                isPlaying = controller.isPlaying
                playbackState = controller.playbackState
                currentName = controller.currentMediaItem?.mediaMetadata?.title?.toString()
                currentArtist = controller.currentMediaItem?.mediaMetadata?.artist?.toString() ?: ""
                currentArtwork = controller.currentMediaItem?.mediaMetadata?.artworkUri
            }
            sync()
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
                override fun onPlaybackStateChanged(state: Int) { playbackState = state }
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) { sync() }
            }
            controller.addListener(listener)
            onDispose { controller.removeListener(listener) }
        }
    }

    val favorites by repository.favorites.collectAsState(initial = emptyList())
    val title = currentName ?: "Nic se nepřehrává"

    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("Open Radio", style = MaterialTheme.typography.titleLarge); Text("Internet Radio & Media Player", style = MaterialTheme.typography.labelSmall) } }) },
        bottomBar = {
            Column(Modifier.navigationBarsPadding()) {
                if (currentName != null) MiniPlayer(title, isPlaying, playbackState, controller?.hasPreviousMediaItem() == true, controller?.hasNextMediaItem() == true,
                    onPrevious = { controller?.seekToPreviousMediaItem() }, onNext = { controller?.seekToNextMediaItem() },
                    onPlayPause = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }, onStop = { controller?.stop() },
                    onOpen = { showFullPlayer = true })
                NavigationBar {
                    NavigationBarItem(selectedTab == 0, { selectedTab = 0 }, { Icon(Icons.Default.Home, "Rádia") }, label = { Text("Rádia") })
                    NavigationBarItem(selectedTab == 1, { selectedTab = 1 }, { Icon(Icons.Default.FavoriteBorder, "Oblíbené") }, label = { Text("Oblíbené") })
                    NavigationBarItem(selectedTab == 2, { selectedTab = 2 }, { Icon(Icons.Default.PlayArrow, "Hudba") }, label = { Text("Hudba") })
                    NavigationBarItem(selectedTab == 3, { selectedTab = 3 }, { Icon(Icons.Default.Settings, "Nastavení") }, label = { Text("Nastavení") })
                }
            }
        }
    ) { paddingValues ->
        if (showFullPlayer && currentName != null) {
            FullPlayer(Modifier.padding(paddingValues), controller, title, currentArtist, currentArtwork, isPlaying, playbackState,
                controller?.hasPreviousMediaItem() == true, controller?.hasNextMediaItem() == true,
                { showFullPlayer = false }, { controller?.seekToPreviousMediaItem() }, { controller?.seekToNextMediaItem() },
                { controller?.let { if (it.isPlaying) it.pause() else it.play() } }, { controller?.stop(); showFullPlayer = false })
        } else when (selectedTab) {
            0 -> RadioHome(Modifier.padding(paddingValues), controller, repository)
            1 -> FavoritesScreen(Modifier.padding(paddingValues), favorites, controller, repository)
            2 -> LocalMusicScreen(Modifier.padding(paddingValues), controller, localAudioUris, onPickLocalAudio)
            else -> SettingsScreen(Modifier.padding(paddingValues), preferences)
        }
    }
}

@Composable
private fun FullPlayer(modifier: Modifier, controller: MediaController?, name: String, artist: String, artworkUri: android.net.Uri?, isPlaying: Boolean,
                       playbackState: Int, canGoPrevious: Boolean, canGoNext: Boolean, onClose: () -> Unit, onPrevious: () -> Unit,
                       onNext: () -> Unit, onPlayPause: () -> Unit, onStop: () -> Unit) {
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Zavřít") }
            Text("Přehrávač", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Spacer(Modifier.width(48.dp))
        }
        Spacer(Modifier.height(24.dp))
        if (artworkUri != null) {
            coil.compose.AsyncImage(model = artworkUri, contentDescription = null, modifier = Modifier.width(240.dp).height(240.dp))
        } else {
            Surface(Modifier.width(240.dp).height(240.dp), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 4.dp) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.PlayArrow, null); Text("Open Radio", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (artist.isNotBlank()) Text(artist, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(when (playbackState) { Player.STATE_BUFFERING -> "Připojování…"; Player.STATE_READY -> if (isPlaying) "Hraje" else "Pozastaveno"; Player.STATE_ENDED -> "Konec"; else -> "Připraveno" }, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, enabled = canGoPrevious) { Icon(Icons.Default.SkipPrevious, "Předchozí") }
            IconButton(onClick = onPlayPause) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Přehrát / pozastavit") }
            IconButton(onClick = onNext, enabled = canGoNext) { Icon(Icons.Default.SkipNext, "Další") }
        }
        Spacer(Modifier.height(16.dp))
        Surface(onClick = onStop, shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) { Text("Zastavit", Modifier.padding(horizontal = 28.dp, vertical = 12.dp)) }
    }
}

@Composable
private fun RadioHome(modifier: Modifier, controller: MediaController?, repository: StationRepository) {
    var searchText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val favorites by repository.favorites.collectAsState(initial = emptyList())
    val stationsFlow = remember(searchText) { if (searchText.isBlank()) repository.stations else repository.search(searchText.trim()) }
    val stations by stationsFlow.collectAsState(initial = emptyList())
    suspend fun refreshStations(query: String) {
        loading = true; errorText = null
        try { repository.syncStations(withContext(Dispatchers.IO) { if (query.isBlank()) RadioBrowserApi.loadCzechStations() else RadioBrowserApi.searchStations(query) }) }
        catch (_: Exception) { errorText = "Nepodařilo se aktualizovat stanice. Zobrazuji uložená data." }
        finally { loading = false }
    }
    LaunchedEffect(Unit) { repository.syncStations(listOf(DefaultStations.fajnRock)); refreshStations("") }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(4.dp))
        if (favorites.isNotEmpty()) {
            Text("Oblíbená rádia", style = MaterialTheme.typography.headlineSmall)
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(favorites, key = { it.id }) { station -> FavoriteStationCard(station, controller) { scope.launch { repository.toggleFavorite(station) } } } }
        }
        Text("Objev rádia", style = MaterialTheme.typography.headlineSmall)
        Text("Stanice se ukládají do zařízení a oblíbené zůstávají i offline.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(searchText, { searchText = it }, Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, label = { Text("Hledat rádio") }, placeholder = { Text("Rock, Fajn, Radio…") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(onClick = { scope.launch { refreshStations(searchText) } }, Modifier.weight(1f), shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) { Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Search, null); Text("  Hledat") } }
            Surface(onClick = { searchText = ""; scope.launch { refreshStations("") } }, Modifier.weight(1f), shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) { Text("Česká rádia", Modifier.fillMaxWidth().padding(12.dp), textAlign = TextAlign.Center) }
        }
        when { loading -> Text("Aktualizuji stanice…"); errorText != null -> Text(errorText!!); else -> Text("${stations.size} uložených stanic", style = MaterialTheme.typography.labelMedium) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(stations, key = { it.id }) { station -> StationRow(station, controller) { scope.launch { repository.toggleFavorite(station) } } } }
    }
}

@Composable
private fun FavoriteStationCard(station: Station, controller: MediaController?, onFavorite: () -> Unit) {
    val active = controller?.currentMediaItem?.mediaId == station.id
    Card(onClick = { playStation(controller, station) }, Modifier.width(156.dp)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) { StationLogo(station); Spacer(Modifier.height(8.dp)); Text(station.name, MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(if (active) "▶ Hraje" else "▶ Přehrát", style = MaterialTheme.typography.labelSmall); IconButton(onClick = onFavorite) { Icon(Icons.Default.Favorite, "Odebrat z oblíbených") } }
    }
}

@Composable
private fun FavoritesScreen(modifier: Modifier, favorites: List<Station>, controller: MediaController?, repository: StationRepository) {
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(4.dp)); Text("Oblíbená rádia", style = MaterialTheme.typography.headlineSmall); Text("Uloženo v telefonu", style = MaterialTheme.typography.bodyMedium)
        if (favorites.isEmpty()) Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.FavoriteBorder, null); Spacer(Modifier.height(8.dp)); Text("Zatím nemáš žádné oblíbené rádio."); Text("Klepni na srdce u stanice.", style = MaterialTheme.typography.bodySmall) }
        else LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(favorites, key = { it.id }) { station -> StationRow(station, controller) { scope.launch { repository.toggleFavorite(station) } } } }
    }
}

@Composable
private fun StationRow(station: Station, controller: MediaController?, onFavorite: () -> Unit) {
    val active = controller?.currentMediaItem?.mediaId == station.id
    Card(onClick = { playStation(controller, station) }, Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StationLogo(station); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(station.name, MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(if (active) "▶ Právě hraje" else "Internet Radio", MaterialTheme.typography.bodySmall) }
            IconButton(onClick = onFavorite) { Icon(if (station.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (station.isFavorite) "Odebrat z oblíbených" else "Přidat do oblíbených") }
            Icon(if (active) Icons.Default.Pause else Icons.Default.PlayArrow, if (active) "Právě hraje" else "Přehrát")
        }
    }
}

private fun playStation(controller: MediaController?, station: Station) {
    controller?.let { player ->
        if (!station.id.startsWith("custom-")) RadioBrowserApi.registerClick(station.id)
        val item = MediaItem.Builder().setMediaId(station.id).setUri(station.streamUrl).setMediaMetadata(MediaMetadata.Builder().setTitle(station.name).setArtist("Internet Radio").setArtworkUri(station.logoUrl?.let { android.net.Uri.parse(it) }).build()).build()
        contextForSettings = player
        if (player.currentMediaItem?.mediaId != station.id) { player.setMediaItem(item); player.prepare() }
        player.play()
        lastRadioStore(item)
    }
}

private var contextForSettings: MediaController? = null
private fun lastRadioStore(item: MediaItem) {
    val player = contextForSettings ?: return
    val context = player.context
    context.getSharedPreferences("last_radio", android.content.Context.MODE_PRIVATE).edit()
        .putString("id", item.mediaId)
        .putString("url", item.localConfiguration?.uri?.toString())
        .putString("name", item.mediaMetadata.title?.toString())
        .putString("logo", item.mediaMetadata.artworkUri?.toString())
        .apply()
}

private suspend fun readLocalTrack(uri: android.net.Uri, context: android.content.Context, fallbackIndex: Int): LocalTrack = withContext(Dispatchers.IO) {
    var retriever: MediaMetadataRetriever? = null
    try {
        retriever = MediaMetadataRetriever(); retriever.setDataSource(context, uri)
        val fallbackTitle = uri.lastPathSegment?.substringAfterLast('/') ?: "Lokální skladba ${fallbackIndex + 1}"
        LocalTrack(uri.toString().hashCode().toLong(), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: fallbackTitle, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() } ?: "Lokální hudba", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L, uri.toString())
    } catch (_: Exception) { LocalTrack(uri.toString().hashCode().toLong(), uri.lastPathSegment?.substringAfterLast('/') ?: "Lokální skladba ${fallbackIndex + 1}", "Lokální hudba", "", 0L, uri.toString()) }
    finally { retriever?.release() }
}

@Composable
private fun LocalMusicScreen(modifier: Modifier, controller: MediaController?, localAudioUris: List<android.net.Uri>, onPickAudio: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tracks by remember(localAudioUris) { mutableStateOf<List<LocalTrack>>(emptyList()) }
    var loading by remember(localAudioUris) { mutableStateOf(localAudioUris.isNotEmpty()) }
    LaunchedEffect(localAudioUris) { loading = localAudioUris.isNotEmpty(); tracks = localAudioUris.mapIndexed { index, uri -> readLocalTrack(uri, context, index) }; loading = false }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(4.dp)); Text("Lokální hudba", style = MaterialTheme.typography.headlineSmall); Text("Vyber hudební soubory uložené v telefonu.", style = MaterialTheme.typography.bodyMedium)
        Surface(onClick = onPickAudio, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) { Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.PlayArrow, null); Text("  Přidat hudbu") } }
        when {
            loading -> Text("Načítám metadata skladeb…")
            tracks.isEmpty() -> Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { Text("Knihovna je zatím prázdná."); Text("Vyber jeden nebo více audio souborů.", style = MaterialTheme.typography.bodySmall) }
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(tracks, key = { it.id }) { track ->
                val active = controller?.currentMediaItem?.mediaId == "local:${track.id}"
                Card(onClick = { controller?.let { player -> val items = tracks.map { localTrack -> MediaItem.Builder().setMediaId("local:${localTrack.id}").setUri(localTrack.contentUri).setMediaMetadata(MediaMetadata.Builder().setTitle(localTrack.title).setArtist(localTrack.artist).setAlbumTitle(localTrack.album).build()).build() }; player.setMediaItems(items, tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0), 0L); player.prepare(); player.play() } }, Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(track.title, MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(if (track.album.isBlank()) track.artist else "${track.artist} • ${track.album}", MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }; Icon(if (active) Icons.Default.Pause else Icons.Default.PlayArrow, if (active) "Právě hraje" else "Přehrát") } }
            } }
        }
    }
}

@Composable
private fun MiniPlayer(name: String, isPlaying: Boolean, playbackState: Int, canGoPrevious: Boolean, canGoNext: Boolean, onPrevious: () -> Unit, onNext: () -> Unit, onPlayPause: () -> Unit, onStop: () -> Unit, onOpen: () -> Unit) {
    Surface(onClick = onOpen, tonalElevation = 4.dp) { Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, enabled = canGoPrevious) { Icon(Icons.Default.SkipPrevious, "Předchozí") }
        Column(Modifier.weight(1f)) { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall); Text(when { isPlaying -> "Hraje"; playbackState == Player.STATE_BUFFERING -> "Připojování…"; else -> "Pozastaveno" }, style = MaterialTheme.typography.labelSmall) }
        IconButton(onClick = onPlayPause) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Přehrát / pozastavit") }
        IconButton(onClick = onNext, enabled = canGoNext) { Icon(Icons.Default.SkipNext, "Další") }
        IconButton(onClick = onStop) { Text("■", style = MaterialTheme.typography.titleMedium) }
    } }
}

@Composable
private fun SettingsScreen(modifier: Modifier, preferences: android.content.SharedPreferences) {
    var autoStart by remember { mutableStateOf(preferences.getBoolean("auto_start", false)) }
    var reconnect by remember { mutableStateOf(preferences.getBoolean("reconnect", true)) }
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Nastavení", style = MaterialTheme.typography.headlineSmall)
        Text("Přehrávání", style = MaterialTheme.typography.titleMedium)
        SettingSwitch("Spustit poslední rádio při otevření aplikace", "Přehrávání začne pouze při otevření aplikace, nikdy samo po startu telefonu.", autoStart) { autoStart = it; preferences.edit().putBoolean("auto_start", it).apply() }
        SettingSwitch("Automaticky obnovit síťové rádio", "Při krátkém výpadku se přehrávač pokusí znovu připojit.", reconnect) { reconnect = it; preferences.edit().putBoolean("reconnect", it).apply() }
        Text("Aplikace", style = MaterialTheme.typography.titleMedium)
        Text("Open Radio & Media Player", style = MaterialTheme.typography.bodyMedium)
        Text("Přehrávání rádia i lokální hudby běží přes Media3.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyLarge); Text(description, style = MaterialTheme.typography.bodySmall) }; Switch(checked, onCheckedChange) }
}
