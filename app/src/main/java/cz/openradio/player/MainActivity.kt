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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private var controller: MediaController? by mutableStateOf(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener(
            { controller = future.get() },
            mainExecutor
        )

        setContent {
            MaterialTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Open Radio & Media Player") }
                        )
                    }
                ) { padding ->
                    HomeScreen(
                        modifier = Modifier.padding(padding),
                        controller = controller
                    )
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
private fun HomeScreen(
    modifier: Modifier = Modifier,
    controller: MediaController?
) {
    var isPlaying by mutableStateOf(controller?.isPlaying == true)
    var playbackState by mutableStateOf(controller?.playbackState ?: Player.STATE_IDLE)

    DisposableEffect(controller) {
        if (controller == null) {
            onDispose { }
        } else {
            isPlaying = controller.isPlaying
            playbackState = controller.playbackState

            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlaybackStateChanged(state: Int) {
                    playbackState = state
                }
            }
            controller.addListener(listener)
            onDispose { controller.removeListener(listener) }
        }
    }

    val station = DefaultStations.fajnRock

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(
            text = "Internet Radio",
            style = MaterialTheme.typography.labelLarge
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "📻 ${station.name}",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "Internet Radio",
                    style = MaterialTheme.typography.bodyLarge
                )

                Text(
                    text = when {
                        isPlaying -> "Hraje"
                        playbackState == Player.STATE_BUFFERING -> "Načítám stream…"
                        playbackState == Player.STATE_IDLE -> "Připraveno"
                        else -> "Pozastaveno"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = controller != null,
                    onClick = {
                        if (controller?.isPlaying == true) {
                            controller.pause()
                        } else {
                            controller?.let { player ->
                                if (player.currentMediaItem == null || player.currentMediaItem?.mediaId != station.id) {
                                    player.setMediaItem(
                                        MediaItem.Builder()
                                            .setMediaId(station.id)
                                            .setUri(station.streamUrl)
                                            .setMediaMetadata(
                                                MediaMetadata.Builder()
                                                    .setTitle(station.name)
                                                    .setArtist("Internet Radio")
                                                    .build()
                                            )
                                            .build()
                                    )
                                    player.prepare()
                                }
                                player.play()
                            }
                        }
                    }
                ) {
                    Text(if (isPlaying) "PAUSE" else "PLAY")
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = controller?.currentMediaItem != null,
                    onClick = {
                        controller?.stop()
                        isPlaying = false
                        playbackState = Player.STATE_IDLE
                    }
                ) {
                    Text("STOP")
                }
            }
        }

        Text(
            text = "Přehrávání běží přes Media3 MediaSessionService, takže může pokračovat na pozadí i po zamknutí telefonu.",
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "Notifikace umožní ovládání rádia i po zamknutí telefonu.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
