package cz.openradio.player

import android.content.ComponentName
import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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

    private val localAudioPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some document providers do not offer persistable permissions.
            }
        }
        localAudioSelection = uris
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({ controller = future.get() }, mainExecutor)

        setContent {
            MaterialTheme {
                RadioPlayerApp(
                    controller = controller,
                    localAudioUris = localAudioSelection,
                    onPickLocalAudio = {
                        localAudioPicker.launch(arrayOf("audio/*"))
                    }
                )
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
private fun RadioPlayerApp(
    controller: MediaController?,
    localAudioUris: List<android.net.Uri>,
    onPickLocalAudio: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember {
        StationRepository(AppDatabase.getInstance(context).stationDao())
    }
    var selectedTab by remember { mutableStateOf(0) }
    var isPlaying by remember(controller) { mutableStateOf(controller?.isPlaying == true) }
    var playbackState by remember(controller) { mutableStateOf(controller?.playbackState ?: Player.STATE_IDLE) }
    var currentName by remember(controller) { mutableStateOf(controller?.currentMediaItem?.mediaMetadata?.title?.toString()) }

    DisposableEffect(controller) {
        if (controller == null) {
            onDispose { }
        } else {
            isPlaying = controller.isPlaying
            playbackState = controller.playbackState
            currentName = controller.currentMediaItem?.mediaMetadata?.title?.toString()
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
                override fun onPlaybackStateChanged(state: Int) { playbackState = state }
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    currentName = item?.mediaMetadata?.title?.toString()
                }
            }
            controller.addListener(listener)
            onDispose { controller.removeListener(listener) }
        }
    }

    val favorites by repository.favorites.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Open Radio", style = MaterialTheme.typography.titleLarge)
                        Text("Internet Radio & Media Player", style = MaterialTheme.typography.labelSmall)
                    }
                }
            )
        },
        bottomBar = {
            Column(modifier = Modifier.navigationBarsPadding()) {
                if (currentName != null) {
                    MiniPlayer(
                        name = currentName!!,
                        isPlaying = isPlaying,
                        playbackState = playbackState,
                        onPlayPause = {
                            controller?.let { if (it.isPlaying) it.pause() else it.play() }
                        },
                        onStop = { controller?.stop() }
                    )
                }
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Rádia") },
                        label = { Text("Rádia") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.FavoriteBorder, contentDescription = "Oblíbené") },
                        label = { Text("Oblíbené") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Hudba") },
                        label = { Text("Hudba") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Nastavení") },
                        label = { Text("Nastavení") }
                    )
                }
            }
        }
    ) { paddingValues ->
        when (selectedTab) {
            0 -> RadioHome(
                modifier = Modifier.padding(paddingValues),
                controller = controller,
                repository = repository
            )
            1 -> FavoritesScreen(
                modifier = Modifier.padding(paddingValues),
                favorites = favorites,
                controller = controller,
                repository = repository
            )
            2 -> LocalMusicScreen(
                modifier = Modifier.padding(paddingValues),
                controller = controller,
                localAudioUris = localAudioUris,
                onPickAudio = onPickLocalAudio
            )
            else -> PlaceholderScreen(Modifier.padding(paddingValues), "Nastavení", "Přehrávání, vzhled, automatické spuštění a další nastavení.")
        }
    }
}

@Composable
private fun RadioHome(
    modifier: Modifier,
    controller: MediaController?,
    repository: StationRepository
) {
    var searchText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val stationsFlow = remember(searchText) {
        if (searchText.isBlank()) repository.stations else repository.search(searchText.trim())
    }
    val stations by stationsFlow.collectAsState(initial = emptyList())

    suspend fun refreshStations(query: String) {
        loading = true
        errorText = null
        try {
            val result = withContext(Dispatchers.IO) {
                if (query.isBlank()) RadioBrowserApi.loadCzechStations() else RadioBrowserApi.searchStations(query)
            }
            repository.syncStations(result)
        } catch (_: Exception) {
            errorText = "Nepodařilo se aktualizovat stanice. Zobrazuji uložená data."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        repository.syncStations(listOf(DefaultStations.fajnRock))
        refreshStations("")
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Text("Objev rádia", style = MaterialTheme.typography.headlineSmall)
        Text("Stanice se ukládají do zařízení a oblíbené zůstávají i offline.", style = MaterialTheme.typography.bodyMedium)

        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            label = { Text("Hledat rádio") },
            placeholder = { Text("Rock, Fajn, Radio…") }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                onClick = { scope.launch { refreshStations(searchText) } },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Text("  Hledat")
                }
            }
            Surface(
                onClick = {
                    searchText = ""
                    scope.launch { refreshStations("") }
                },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 2.dp
            ) {
                Text("Česká rádia", modifier = Modifier.fillMaxWidth().padding(12.dp), textAlign = TextAlign.Center)
            }
        }

        when {
            loading -> Text("Aktualizuji stanice…")
            errorText != null -> Text(errorText!!)
            else -> Text("${stations.size} uložených stanic", style = MaterialTheme.typography.labelMedium)
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(stations, key = { it.id }) { station ->
                StationRow(
                    station = station,
                    controller = controller,
                    onFavorite = { scope.launch { repository.toggleFavorite(station) } }
                )
            }
        }
    }
}

@Composable
private fun FavoritesScreen(
    modifier: Modifier,
    favorites: List<Station>,
    controller: MediaController?,
    repository: StationRepository
) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Text("Oblíbená rádia", style = MaterialTheme.typography.headlineSmall)
        Text("Uloženo v telefonu", style = MaterialTheme.typography.bodyMedium)

        if (favorites.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.FavoriteBorder, contentDescription = null)
                Spacer(Modifier.height(8.dp))
                Text("Zatím nemáš žádné oblíbené rádio.")
                Text("Klepni na srdce u stanice.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(favorites, key = { it.id }) { station ->
                    StationRow(
                        station = station,
                        controller = controller,
                        onFavorite = { scope.launch { repository.toggleFavorite(station) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun StationRow(
    station: Station,
    controller: MediaController?,
    onFavorite: () -> Unit
) {
    val currentId = controller?.currentMediaItem?.mediaId
    val active = currentId == station.id

    Card(
        onClick = {
            controller?.let { player ->
                RadioBrowserApi.registerClick(station.id)
                val item = MediaItem.Builder()
                    .setMediaId(station.id)
                    .setUri(station.streamUrl)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(station.name)
                            .setArtist("Internet Radio")
                            .build()
                    )
                    .build()
                if (player.currentMediaItem?.mediaId != station.id) {
                    player.setMediaItem(item)
                    player.prepare()
                }
                player.play()
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = station.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (active) "▶ Právě hraje" else "Internet Radio",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    imageVector = if (station.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (station.isFavorite) "Odebrat z oblíbených" else "Přidat do oblíbených"
                )
            }
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = if (active) "Právě hraje" else "Přehrát"
            )
        }
    }
}

@Composable
private fun LocalMusicScreen(
    modifier: Modifier,
    controller: MediaController?,
    localAudioUris: List<android.net.Uri>,
    onPickAudio: () -> Unit
) {
    val tracks = remember(localAudioUris) {
        localAudioUris.mapIndexed { index, uri ->
            LocalTrack(
                id = index.toLong(),
                title = uri.lastPathSegment?.substringAfterLast('/') ?: "Lokální skladba ${index + 1}",
                artist = "Lokální hudba",
                album = "",
                durationMs = 0L,
                contentUri = uri.toString()
            )
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Text("Lokální hudba", style = MaterialTheme.typography.headlineSmall)
        Text("Vyber hudební soubory uložené v telefonu.", style = MaterialTheme.typography.bodyMedium)

        Surface(
            onClick = onPickAudio,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Text("  Přidat hudbu")
            }
        }

        if (tracks.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Knihovna je zatím prázdná.")
                Text("Vyber jeden nebo více audio souborů.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tracks, key = { it.id }) { track ->
                    Card(
                        onClick = {
                            controller?.let { player ->
                                val item = MediaItem.Builder()
                                    .setMediaId("local:${track.id}")
                                    .setUri(track.contentUri)
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(track.title)
                                            .setArtist(track.artist)
                                            .setAlbumTitle(track.album)
                                            .build()
                                    )
                                    .build()
                                player.setMediaItem(item)
                                player.prepare()
                                player.play()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(track.artist, style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Default.PlayArrow, contentDescription = "Přehrát")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    name: String,
    isPlaying: Boolean,
    playbackState: Int,
    onPlayPause: () -> Unit,
    onStop: () -> Unit
) {
    Surface(tonalElevation = 4.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        isPlaying -> "Hraje"
                        playbackState == Player.STATE_BUFFERING -> "Připojování…"
                        else -> "Pozastaveno"
                    },
                    style = MaterialTheme.typography.labelSmall
                )
            }
            IconButton(onClick = onPlayPause) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Přehrát / pozastavit")
            }
            IconButton(onClick = onStop) {
                Text("■", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(modifier: Modifier, title: String, text: String) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}
