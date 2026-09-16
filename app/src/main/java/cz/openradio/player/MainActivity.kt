@file:OptIn(ExperimentalMaterial3Api::class)

package cz.openradio.player

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var controller: MediaController? by mutableStateOf(null)
    private var localAudioSelection by mutableStateOf<List<Uri>>(emptyList())

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

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        localAudioSelection = getSharedPreferences("local_music", MODE_PRIVATE).getString("uris", "")
            ?.split('\n')?.filter { it.isNotBlank() }?.map(Uri::parse) ?: emptyList()

        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

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
                if (id != null && url != null && name != null && controller?.currentMediaItem == null) {
                    controller?.setMediaItem(MediaItem.Builder().setMediaId(id).setUri(url).setMediaMetadata(
                        MediaMetadata.Builder().setTitle(name).setArtist("Internet Radio").setArtworkUri(logo?.let(Uri::parse)).build()
                    ).build())
                    controller?.prepare()
                    controller?.play()
                }
            }
        }, mainExecutor)

        setContent { MaterialTheme { RadioPlayerApp(controller, localAudioSelection) { localAudioPicker.launch(arrayOf("audio/*")) } } }
    }

    override fun onDestroy() {
        controller?.release()
        controller = null
        super.onDestroy()
    }
}

@Composable
private fun RadioPlayerApp(controller: MediaController?, localAudioUris: List<Uri>, onPickLocalAudio: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { StationRepository(AppDatabase.getInstance(context).stationDao()) }
    val preferences = remember { context.getSharedPreferences("playback_settings", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }
    var showFullPlayer by remember { mutableStateOf(false) }
    var showAddStation by remember { mutableStateOf(false) }
    var isPlaying by remember(controller) { mutableStateOf(controller?.isPlaying == true) }
    var playbackState by remember(controller) { mutableStateOf(controller?.playbackState ?: Player.STATE_IDLE) }
    var currentName by remember(controller) { mutableStateOf(controller?.currentMediaItem?.mediaMetadata?.title?.toString()) }
    var currentArtist by remember(controller) { mutableStateOf(controller?.currentMediaItem?.mediaMetadata?.artist?.toString() ?: "") }
    var currentArtwork by remember(controller) { mutableStateOf(controller?.currentMediaItem?.mediaMetadata?.artworkUri) }
    var shuffleEnabled by remember(controller) { mutableStateOf(controller?.shuffleModeEnabled == true) }
    var repeatMode by remember(controller) { mutableStateOf(controller?.repeatMode ?: Player.REPEAT_MODE_OFF) }

    DisposableEffect(controller) {
        if (controller == null) return@DisposableEffect onDispose { }
        fun sync() {
            isPlaying = controller.isPlaying
            playbackState = controller.playbackState
            currentName = controller.currentMediaItem?.mediaMetadata?.title?.toString()
            currentArtist = controller.currentMediaItem?.mediaMetadata?.artist?.toString() ?: ""
            currentArtwork = controller.currentMediaItem?.mediaMetadata?.artworkUri
            shuffleEnabled = controller.shuffleModeEnabled
            repeatMode = controller.repeatMode
        }
        sync()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
            override fun onPlaybackStateChanged(state: Int) { playbackState = state }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) { sync() }
            override fun onShuffleModeEnabledChanged(value: Boolean) { shuffleEnabled = value }
            override fun onRepeatModeChanged(value: Int) { repeatMode = value }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    val favorites by repository.favorites.collectAsState(initial = emptyList())
    val title = currentName ?: "Nic se nepřehrává"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("Open Radio", style = MaterialTheme.typography.titleLarge); Text("Radio & Media Player", style = MaterialTheme.typography.labelSmall) } },
                actions = { if (selectedTab == 0) IconButton(onClick = { showAddStation = true }) { Icon(Icons.Default.Add, "Přidat rádio") } }
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding()) {
                if (currentName != null) MiniPlayer(title, isPlaying, playbackState,
                    controller?.hasPreviousMediaItem() == true, controller?.hasNextMediaItem() == true,
                    { controller?.seekToPreviousMediaItem() }, { controller?.seekToNextMediaItem() },
                    { controller?.let { if (it.isPlaying) it.pause() else it.play() } }, { controller?.stop() },
                    { showFullPlayer = true })
                NavigationBar {
                    NavigationBarItem(selectedTab == 0, { selectedTab = 0 }, icon = { Icon(Icons.Default.Home, "Rádia") }, label = { Text("Rádia") })
                    NavigationBarItem(selectedTab == 1, { selectedTab = 1 }, icon = { Icon(Icons.Default.FavoriteBorder, "Oblíbené") }, label = { Text("Oblíbené") })
                    NavigationBarItem(selectedTab == 2, { selectedTab = 2 }, icon = { Icon(Icons.Default.LibraryMusic, "Hudba") }, label = { Text("Hudba") })
                    NavigationBarItem(selectedTab == 3, { selectedTab = 3 }, icon = { Icon(Icons.Default.Settings, "Nastavení") }, label = { Text("Nastavení") })
                }
            }
        }
    ) { padding ->
        if (showFullPlayer && currentName != null) {
            FullPlayer(Modifier.padding(padding), title, currentArtist, currentArtwork, isPlaying, playbackState,
                controller?.hasPreviousMediaItem() == true, controller?.hasNextMediaItem() == true, shuffleEnabled, repeatMode,
                { showFullPlayer = false }, { controller?.seekToPreviousMediaItem() }, { controller?.seekToNextMediaItem() },
                { controller?.let { if (it.isPlaying) it.pause() else it.play() } }, { controller?.stop(); showFullPlayer = false },
                { controller?.shuffleModeEnabled = !shuffleEnabled }, {
                    controller?.repeatMode = when (repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
                })
        } else when (selectedTab) {
            0 -> RadioHome(Modifier.padding(padding), controller, repository)
            1 -> FavoritesScreen(Modifier.padding(padding), favorites, controller, repository)
            2 -> LocalMusicScreen(Modifier.padding(padding), controller, localAudioUris, onPickLocalAudio)
            else -> SettingsScreen(Modifier.padding(padding), preferences)
        }
    }

    if (showAddStation) AddStationDialog(
        onDismiss = { showAddStation = false },
        onSave = { name, url, logo ->
            val station = Station(id = "custom-${System.currentTimeMillis()}", name = name, streamUrl = url, logoUrl = logo)
            scope.launch { repository.syncStations(listOf(station)); showAddStation = false }
        }
    )
}

@Composable
private fun FullPlayer(modifier: Modifier, name: String, artist: String, artworkUri: Uri?, isPlaying: Boolean, playbackState: Int,
                       canGoPrevious: Boolean, canGoNext: Boolean, shuffleEnabled: Boolean, repeatMode: Int,
                       onClose: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onPlayPause: () -> Unit,
                       onStop: () -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit) {
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Zavřít") }
            Text("Přehrávač", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Spacer(Modifier.width(48.dp))
        }
        Spacer(Modifier.height(20.dp))
        if (artworkUri != null) AsyncImage(model = artworkUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(250.dp).clip(MaterialTheme.shapes.extraLarge))
        else Surface(Modifier.size(250.dp), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 4.dp) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.Radio, null, Modifier.size(64.dp)); Text("Open Radio", style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (artist.isNotBlank()) Text(artist, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(when (playbackState) { Player.STATE_BUFFERING -> "Připojování…"; Player.STATE_READY -> if (isPlaying) "Hraje" else "Pozastaveno"; Player.STATE_ENDED -> "Konec"; else -> "Připraveno" }, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, enabled = canGoPrevious) { Icon(Icons.Default.SkipPrevious, "Předchozí") }
            FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(68.dp), shape = CircleShape) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Přehrát / pozastavit", Modifier.size(36.dp)) }
            IconButton(onClick = onNext, enabled = canGoNext) { Icon(Icons.Default.SkipNext, "Další") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            IconButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, "Náhodné přehrávání", tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
            IconButton(onClick = onRepeat) { Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Opakování") }
            IconButton(onClick = onStop) { Icon(Icons.Default.Stop, "Zastavit") }
        }
    }
}

@Composable
private fun RadioHome(modifier: Modifier, controller: MediaController?, repository: StationRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val favorites by repository.favorites.collectAsState(initial = emptyList())
    val stationsFlow = remember(searchText) { if (searchText.isBlank()) repository.stations else repository.search(searchText.trim()) }
    val stations by stationsFlow.collectAsState(initial = emptyList())

    suspend fun refresh(query: String) {
        loading = true; errorText = null
        try { repository.syncStations(withContext(Dispatchers.IO) { if (query.isBlank()) RadioBrowserApi.loadCzechStations() else RadioBrowserApi.searchStations(query) }) }
        catch (_: Exception) { errorText = "Nepodařilo se aktualizovat stanice. Zobrazuji uložená data." }
        finally { loading = false }
    }
    LaunchedEffect(Unit) { repository.syncStations(listOf(DefaultStations.fajnRock)); refresh("") }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(4.dp))
        if (favorites.isNotEmpty()) {
            Text("Oblíbená rádia", style = MaterialTheme.typography.headlineSmall)
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { items(favorites, key = { it.id }) { station -> FavoriteStationCard(station, controller, context) { scope.launch { repository.toggleFavorite(station) } } } }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Objev rádia", style = MaterialTheme.typography.headlineSmall); Text("Česká rádia a další stanice z Radio Browser", style = MaterialTheme.typography.bodyMedium) }
            IconButton(onClick = { scope.launch { refresh(searchText) } }) { Icon(Icons.Default.Refresh, "Aktualizovat") }
        }
        OutlinedTextField(value = searchText, onValueChange = { searchText = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) }, label = { Text("Hledat rádio") }, placeholder = { Text("Rock, Fajn, Radio…") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { scope.launch { refresh(searchText) } }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)); Text("Hledat") }
            OutlinedButton(onClick = { searchText = ""; scope.launch { refresh("") } }, modifier = Modifier.weight(1f)) { Text("Česká rádia") }
        }
        when { loading -> LinearProgressIndicator(Modifier.fillMaxWidth()); errorText != null -> Text(errorText!!, style = MaterialTheme.typography.bodySmall); else -> Text("${stations.size} stanic", style = MaterialTheme.typography.labelMedium) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(stations, key = { it.id }) { station -> StationRow(station, controller, context) { scope.launch { repository.toggleFavorite(station) } } } }
    }
}

@Composable
private fun FavoriteStationCard(station: Station, controller: MediaController?, context: Context, onFavorite: () -> Unit) {
    val active = controller?.currentMediaItem?.mediaId == station.id
    Card(onClick = { playStation(controller, station, context) }, Modifier.width(160.dp)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StationLogo(station); Spacer(Modifier.height(8.dp))
            Text(station.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (active) "▶ Hraje" else "▶ Přehrát", style = MaterialTheme.typography.labelSmall)
            IconButton(onClick = onFavorite) { Icon(Icons.Default.Favorite, "Odebrat z oblíbených") }
        }
    }
}

@Composable
private fun FavoritesScreen(modifier: Modifier, favorites: List<Station>, controller: MediaController?, repository: StationRepository) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(4.dp)); Text("Oblíbená rádia", style = MaterialTheme.typography.headlineSmall); Text("Uloženo v telefonu", style = MaterialTheme.typography.bodyMedium)
        if (favorites.isEmpty()) Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.FavoriteBorder, null, Modifier.size(48.dp)); Spacer(Modifier.height(8.dp)); Text("Zatím nemáš žádné oblíbené rádio."); Text("Klepni na srdce u stanice.", style = MaterialTheme.typography.bodySmall)
        } else LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(favorites, key = { it.id }) { station -> StationRow(station, controller, context) { scope.launch { repository.toggleFavorite(station) } } } }
    }
}

@Composable
private fun StationRow(station: Station, controller: MediaController?, context: Context, onFavorite: () -> Unit) {
    val active = controller?.currentMediaItem?.mediaId == station.id
    Card(onClick = { playStation(controller, station, context) }, Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StationLogo(station); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) {
                Text(station.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (active) "▶ Právě hraje" else "Internet Radio", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onFavorite) { Icon(if (station.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (station.isFavorite) "Odebrat z oblíbených" else "Přidat do oblíbených") }
            Icon(if (active) Icons.Default.Pause else Icons.Default.PlayArrow, if (active) "Právě hraje" else "Přehrát")
        }
    }
}

private fun playStation(controller: MediaController?, station: Station, context: Context) {
    controller?.let { player ->
        if (!station.id.startsWith("custom-")) RadioBrowserApi.registerClick(station.id)
        val item = MediaItem.Builder().setMediaId(station.id).setUri(station.streamUrl).setMediaMetadata(
            MediaMetadata.Builder().setTitle(station.name).setArtist("Internet Radio").setArtworkUri(station.logoUrl?.let(Uri::parse)).build()).build()
        if (player.currentMediaItem?.mediaId != station.id) { player.setMediaItem(item); player.prepare() }
        player.play()
        context.getSharedPreferences("last_radio", Context.MODE_PRIVATE).edit()
            .putString("id", item.mediaId).putString("url", item.localConfiguration?.uri?.toString()).putString("name", item.mediaMetadata.title?.toString()).putString("logo", item.mediaMetadata.artworkUri?.toString()).apply()
    }
}

private suspend fun readLocalTrack(uri: Uri, context: Context, fallbackIndex: Int): LocalTrack = withContext(Dispatchers.IO) {
    var retriever: MediaMetadataRetriever? = null
    try {
        retriever = MediaMetadataRetriever(); retriever.setDataSource(context, uri)
        val fallbackTitle = uri.lastPathSegment?.substringAfterLast('/') ?: "Lokální skladba ${fallbackIndex + 1}"
        LocalTrack(uri.toString().hashCode().toLong(), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() } ?: "Lokální hudba",
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L, uri.toString())
    } catch (_: Exception) {
        LocalTrack(uri.toString().hashCode().toLong(), uri.lastPathSegment?.substringAfterLast('/') ?: "Lokální skladba ${fallbackIndex + 1}", "Lokální hudba", "", 0L, uri.toString())
    } finally { retriever?.release() }
}

@Composable
private fun LocalMusicScreen(modifier: Modifier, controller: MediaController?, localAudioUris: List<Uri>, onPickAudio: () -> Unit) {
    val context = LocalContext.current
    var tracks by remember(localAudioUris) { mutableStateOf<List<LocalTrack>>(emptyList()) }
    var loading by remember(localAudioUris) { mutableStateOf(localAudioUris.isNotEmpty()) }
    LaunchedEffect(localAudioUris) { loading = localAudioUris.isNotEmpty(); tracks = localAudioUris.mapIndexed { index, uri -> readLocalTrack(uri, context, index) }; loading = false }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(4.dp)); Text("Lokální hudba", style = MaterialTheme.typography.headlineSmall); Text("Hudba vybraná z telefonu zůstává dostupná i po restartu aplikace.", style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onClick = onPickAudio, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.LibraryAdd, null); Spacer(Modifier.width(8.dp)); Text("Přidat hudbu") }
        when {
            loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            tracks.isEmpty() -> Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.LibraryMusic, null, Modifier.size(52.dp)); Spacer(Modifier.height(8.dp)); Text("Knihovna je zatím prázdná."); Text("Vyber jeden nebo více audio souborů.", style = MaterialTheme.typography.bodySmall) }
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tracks, key = { it.id }) { track ->
                    val active = controller?.currentMediaItem?.mediaId == "local:${track.id}"
                    Card(onClick = {
                        controller?.let { player ->
                            val mediaItems = tracks.map { t -> MediaItem.Builder().setMediaId("local:${t.id}").setUri(t.contentUri).setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album).build()).build() }
                            player.setMediaItems(mediaItems, tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0), 0L); player.prepare(); player.play()
                        }
                    }, Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(Modifier.size(48.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) { Icon(Icons.Default.MusicNote, null, Modifier.padding(12.dp)) }
                            Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) {
                                Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (track.album.isBlank()) track.artist else "${track.artist} • ${track.album}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(if (active) Icons.Default.Pause else Icons.Default.PlayArrow, if (active) "Právě hraje" else "Přehrát")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(name: String, isPlaying: Boolean, playbackState: Int, canGoPrevious: Boolean, canGoNext: Boolean, onPrevious: () -> Unit, onNext: () -> Unit, onPlayPause: () -> Unit, onStop: () -> Unit, onOpen: () -> Unit) {
    Surface(onClick = onOpen, tonalElevation = 5.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, enabled = canGoPrevious) { Icon(Icons.Default.SkipPrevious, "Předchozí") }
            Column(Modifier.weight(1f)) { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall); Text(when { playbackState == Player.STATE_BUFFERING -> "Připojování…"; isPlaying -> "Hraje"; else -> "Pozastaveno" }, style = MaterialTheme.typography.labelSmall) }
            IconButton(onClick = onPlayPause) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Přehrát / pozastavit") }
            IconButton(onClick = onNext, enabled = canGoNext) { Icon(Icons.Default.SkipNext, "Další") }
            IconButton(onClick = onStop) { Icon(Icons.Default.Stop, "Zastavit") }
        }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier, preferences: android.content.SharedPreferences) {
    var autoStart by remember { mutableStateOf(preferences.getBoolean("auto_start", false)) }
    var reconnect by remember { mutableStateOf(preferences.getBoolean("reconnect", true)) }
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Nastavení", style = MaterialTheme.typography.headlineSmall); Text("Přehrávání", style = MaterialTheme.typography.titleMedium)
        SettingSwitch("Spustit poslední rádio při otevření aplikace", "Přehrávání začne pouze při otevření aplikace, nikdy samo po startu telefonu.", autoStart) { autoStart = it; preferences.edit().putBoolean("auto_start", it).apply() }
        SettingSwitch("Automaticky obnovit síťové rádio", "Při krátkém výpadku se přehrávač pokusí znovu připojit.", reconnect) { reconnect = it; preferences.edit().putBoolean("reconnect", it).apply() }
        HorizontalDivider(); Text("Aplikace", style = MaterialTheme.typography.titleMedium); Text("Open Radio & Media Player", style = MaterialTheme.typography.bodyLarge); Text("Verze 0.2.0", style = MaterialTheme.typography.bodySmall); Text("Rádio i lokální hudba používají Media3 a mohou pokračovat na pozadí.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyLarge); Text(description, style = MaterialTheme.typography.bodySmall) }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
