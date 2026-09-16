package cz.openradio.player

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

class MainActivity : ComponentActivity() {

    private var controller: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                        controllerProvider = { controller }
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
    controllerProvider: () -> MediaController?
) {
    var playing by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(
            text = "📻 Fajn Rock Music",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "První prototyp přehrávače",
            style = MaterialTheme.typography.bodyLarge
        )

        Button(
            onClick = {
                val controller = controllerProvider()
                if (controller == null) return@Button

                if (playing) {
                    controller.pause()
                    playing = false
                } else {
                    val station = DefaultStations.fajnRock
                    val item = MediaItem.Builder()
                        .setMediaId(station.id)
                        .setUri(station.streamUrl)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(station.name)
                                .setArtist("Internet Radio")
                                .build()
                        )
                        .build()

                    controller.setMediaItem(item)
                    controller.prepare()
                    controller.play()
                    playing = true
                }
            }
        ) {
            Text(if (playing) "PAUSE" else "PLAY")
        }

        Text(
            text = "Background playback je připraven přes Media3 MediaSessionService.",
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "Další fáze: Radio Browser + hudba z telefonu + oblíbené stanice.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
