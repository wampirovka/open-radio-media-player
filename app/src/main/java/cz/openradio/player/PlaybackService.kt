package cz.openradio.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            scheduleReconnect()
        }
    }

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this).build().apply {
            setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            addListener(playerListener)
        }

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    private fun scheduleReconnect() {
        val player = mediaSession?.player ?: return

        // If the user paused or stopped playback, do not resurrect the radio.
        if (!player.playWhenReady || player.currentMediaItem == null) return

        reconnectHandler.removeCallbacksAndMessages(null)

        val delayMs = when (reconnectAttempt) {
            0 -> 2_000L
            1 -> 5_000L
            2 -> 10_000L
            else -> 30_000L
        }
        reconnectAttempt++

        reconnectHandler.postDelayed({
            val currentPlayer = mediaSession?.player ?: return@postDelayed
            if (!currentPlayer.playWhenReady || currentPlayer.currentMediaItem == null) return@postDelayed

            currentPlayer.prepare()
            currentPlayer.play()
        }, delayMs)
    }

    fun playStation(station: Station) {
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectAttempt = 0

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

        mediaSession?.player?.setMediaItem(item)
        mediaSession?.player?.prepare()
        mediaSession?.player?.play()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        reconnectHandler.removeCallbacksAndMessages(null)
        mediaSession?.let {
            it.player.removeListener(playerListener)
            it.player.release()
            it.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
