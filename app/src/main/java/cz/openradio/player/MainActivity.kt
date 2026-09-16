package cz.openradio.player

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({ controller = future.get() }, mainExecutor)

        setContent {
            MaterialTheme {
                Scaffold(topBar = { TopAppBar(title = { Text("Open Radio & Media Player") }) }) { padding ->
                    HomeScreen(Modifier.padding(padding), controller)
                }
            }
        }
    }

    override fun onDestroy() {
        controller?.release()
        controller = null
        super.onDestroy()
    }
}

@Composable
private fun HomeScreen(modifier: Modifier = Modifier, controller: MediaController?) {
    var isPlaying by remember(controller) { mutableStateOf(controller?.isPlaying == true) }
    var playbackState by remember(controller) { mutableStateOf(controller?.playbackState ?: Player.STATE_IDLE) }
    var stations by remember { mutableStateOf<List<Station>>(emptyList()) }
    var searchText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun loadStations(query: String) {
        loading = true
        errorText = null
        try {
            stations = withContext(Dispatchers.IO) {
                if (query.isBlank()) RadioBrowserApi.loadCzechStations() else RadioBrowserApi.searchStations(query)
            }
        } catch (_: Exception) {
            errorText = "Nepodařilo se načíst stanice."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadStations("") }

    DisposableEffect(controller) {
        if (controller == null) onDispose { }
        else {
            isPlaying = controller.isPlaying
            playbackState = controller.playbackState
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                override fun onPlaybackStateChanged(state: Int) { playbackState = state }
            }
            controller.addListener(listener)
            onDispose { controller.removeListener(listener) }
        }
    }

    val currentStationId = controller?.currentMediaItem?.mediaId
    val currentStationName = controller?.currentMediaItem?.mediaMetadata?.title?.toString()

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Internet Radio", style = MaterialTheme.typography.labelLarge)

        if (currentStationName != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("📻 $currentStationName", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when {
                            isPlaying -> "Hraje"
                            playbackState == Player.STATE_BUFFERING -> "Připojování…"
                            playbackState == Player.STATE_IDLE -> "Připraveno"
                            else -> "Pozastaveno"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(Modifier.weight(1f), enabled = controller != null, onClick = {
                            controller?.let { player ->
                                if (player.isPlaying) player.pause() else player.play()
                            }
                        }) { Text(if (isPlaying) "PAUSE" else "PLAY") }
                        Button(Modifier.weight(1f), enabled = controller?.currentMediaItem != null, onClick = {
                            controller?.stop()
                        }) { Text("STOP") }
                    }
                }
            }
        }

        OutlinedTextField(
            Modifier.fillMaxWidth(), value = searchText, onValueChange = { searchText = it }, singleLine = true,
            label = { Text("Hledat rádio") }, placeholder = { Text("např. Rock, Radio, Fajn") }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(Modifier.weight(1f), enabled = !loading, onClick = { scope.launch { loadStations(searchText) } }) { Text("HLEDAT") }
            Button(Modifier.weight(1f), enabled = !loading, onClick = {
                searchText = ""
                scope.launch { loadStations("") }
            }) { Text("ČESKÁ RÁDIA") }
        }

        when {
            loading -> Text("Načítám stanice…")
            errorText != null -> Text(errorText!!)
            else -> Text("Stanice: ${stations.size}")
        }

        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(stations, key = { it.id }) { station ->
                Card(Modifier.fillMaxWidth(), onClick = {
                    controller?.let { player ->
                        RadioBrowserApi.registerClick(station.id)
                        val item = MediaItem.Builder()
                            .setMediaId(station.id)
                            .setUri(station.streamUrl)
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(station.name).setArtist("Internet Radio").build())
                            .build()
                        if (player.currentMediaItem?.mediaId != station.id) {
                            player.setMediaItem(item)
                            player.prepare()
                        }
                        player.play()
                    }
                }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(if (station.id == currentStationId) "▶ ${station.name}" else station.name, style = MaterialTheme.typography.titleMedium)
                        Text("Internet Radio", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
