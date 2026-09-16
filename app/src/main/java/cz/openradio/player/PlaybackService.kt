package cz.openradio.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.io.IOException
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            if (isNetworkError(error)) scheduleReconnect()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                reconnectAttempt = 0
                reconnectHandler.removeCallbacksAndMessages(null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,
                60_000,
                2_500,
                7_500
            )
            .build()

        val player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this))
            .build()
            .apply {
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

    private fun isNetworkError(error: androidx.media3.common.PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.HttpDataSourceException || cause is IOException) return true
            cause = cause.cause
        }
        return false
    }

    private fun scheduleReconnect() {
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady || player.currentMediaItem == null) return

        reconnectHandler.removeCallbacksAndMessages(null)
        val delayMs = when (reconnectAttempt) {
            0 -> 10_000L
            1 -> 20_000L
            2 -> 30_000L
            else -> 60_000L
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

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
