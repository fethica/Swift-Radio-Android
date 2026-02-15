package com.fethica.swiftradio

import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class AudioService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var bufferingRecoveryRunnable: Runnable? = null
    private var stallRecoveryRunnable: Runnable? = null
    private var retryCount = 0
    private var lastObservedPositionMs: Long = 0L
    private var lastPositionRealtimeMs: Long = 0L
    private val audioManager: AudioManager by lazy {
        getSystemService(AudioManager::class.java)
    }

    private val playerListener = object : Player.Listener {
        override fun onMetadata(metadata: Metadata) {
            val exoPlayer = player ?: return
            val icyTitle = extractIcyTitle(metadata) ?: return
            val parsedMetadata = buildTrackMetadataFromIcy(icyTitle)
            if (trackMetadataEquivalent(exoPlayer.playlistMetadata, parsedMetadata)) return
            Log.d(
                "SwiftRadio",
                "Svc ICY metadata raw='$icyTitle' title='${parsedMetadata.title}' artist='${parsedMetadata.artist}'"
            )
            exoPlayer.setPlaylistMetadata(parsedMetadata)
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            val exoPlayer = player ?: return
            Log.d(
                "SwiftRadio",
                "Svc media item transition reason=$reason uri='${mediaItem?.localConfiguration?.uri}'"
            )
            if (!isEmptyTrackMetadata(exoPlayer.playlistMetadata)) {
                exoPlayer.setPlaylistMetadata(MediaMetadata.Builder().build())
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e("SwiftRadio", "Svc player error: ${error.errorCodeName} ${error.message}", error)
            retryCurrentItem("player_error")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }
            val exoPlayer = player
            Log.d(
                "SwiftRadio",
                "Svc playback state=$stateName playWhenReady=${exoPlayer?.playWhenReady} isPlaying=${exoPlayer?.isPlaying}"
            )
            logAudioOutputState("state=$stateName")

            when (playbackState) {
                Player.STATE_READY -> {
                    retryCount = 0
                    cancelBufferingRecovery()
                    startStallRecovery()
                }
                Player.STATE_BUFFERING -> scheduleBufferingRecovery()
                Player.STATE_ENDED -> {
                    cancelStallRecovery()
                    Log.w("SwiftRadio", "Svc playback ended; retrying current stream")
                    retryCurrentItem("state_ended")
                }
                Player.STATE_IDLE -> {
                    cancelBufferingRecovery()
                    cancelStallRecovery()
                    if (exoPlayer?.playWhenReady == true) {
                        Log.w("SwiftRadio", "Svc entered IDLE while playWhenReady=true; retrying")
                        retryCurrentItem("state_idle")
                    }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val exoPlayer = player
            Log.d(
                "SwiftRadio",
                "Svc isPlaying=$isPlaying playWhenReady=${exoPlayer?.playWhenReady} " +
                    "suppression=${exoPlayer?.playbackSuppressionReason}"
            )
            logAudioOutputState("isPlaying=$isPlaying")
            if (!isPlaying &&
                exoPlayer?.playWhenReady == true &&
                exoPlayer.playbackState == Player.STATE_READY &&
                exoPlayer.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
            ) {
                Log.w("SwiftRadio", "Svc ready but not playing (no suppression); retrying stream")
                retryCurrentItem("ready_not_playing")
                return
            }
            if (!isPlaying) {
                startStallRecovery()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            Log.d("SwiftRadio", "Svc playWhenReady=$playWhenReady reason=$reason")
            if (playWhenReady) {
                startStallRecovery()
            } else {
                cancelStallRecovery()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        exoPlayer.setAudioAttributes(audioAttributes, true)
        exoPlayer.volume = 1f
        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        exoPlayer.addListener(playerListener)
        player = exoPlayer
        logAudioOutputState("onCreate")
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        player?.removeListener(playerListener)
        cancelBufferingRecovery()
        cancelStallRecovery()
        mainHandler.removeCallbacksAndMessages(null)
        player = null
        mediaSession = null
        super.onDestroy()
    }

    private fun extractIcyTitle(metadata: Metadata): String? {
        for (index in 0 until metadata.length()) {
            val entry = metadata[index]
            if (entry is IcyInfo) {
                val title = entry.title?.trim()
                if (!title.isNullOrBlank()) return title
            }
        }
        return null
    }

    private fun buildTrackMetadataFromIcy(icyTitle: String): MediaMetadata {
        val (artist, title) = parseArtistAndTitle(icyTitle)
        val normalizedTitle = title.ifBlank { icyTitle.trim() }
        return MediaMetadata.Builder()
            .setTitle(normalizedTitle.ifBlank { null })
            .setArtist(artist.ifBlank { null })
            .build()
    }

    private fun parseArtistAndTitle(value: String): Pair<String, String> {
        val separators = listOf(" - ", " – ", " — ")
        separators.forEach { separator ->
            val separatorIndex = value.indexOf(separator)
            if (separatorIndex > 0 && separatorIndex < value.length - separator.length) {
                val artist = value.substring(0, separatorIndex).trim()
                val title = value.substring(separatorIndex + separator.length).trim()
                if (artist.isNotBlank() && title.isNotBlank()) {
                    return artist to title
                }
            }
        }
        return "" to value.trim()
    }

    private fun trackMetadataEquivalent(left: MediaMetadata, right: MediaMetadata): Boolean {
        return left.title?.toString().orEmpty() == right.title?.toString().orEmpty() &&
            left.artist?.toString().orEmpty() == right.artist?.toString().orEmpty()
    }

    private fun isEmptyTrackMetadata(metadata: MediaMetadata): Boolean {
        return metadata.title.isNullOrBlank() && metadata.artist.isNullOrBlank()
    }

    private fun retryCurrentItem(reason: String) {
        val exoPlayer = player ?: return
        if (exoPlayer.mediaItemCount <= 0) return

        retryCount += 1
        val delayMs = when {
            retryCount <= 2 -> 0L
            retryCount <= 5 -> 1000L
            else -> 3000L
        }

        val currentIndex = exoPlayer.currentMediaItemIndex.coerceAtLeast(0)
        val currentItem = exoPlayer.currentMediaItem ?: return
        val currentUri = currentItem.localConfiguration?.uri
        Log.w(
            "SwiftRadio",
            "Svc retry stream reason=$reason attempt=$retryCount delayMs=$delayMs index=$currentIndex uri='$currentUri'"
        )

        mainHandler.postDelayed({
            val activePlayer = player ?: return@postDelayed
            // Hard reset renderer pipeline to recover "silent but playing" audio sink states.
            activePlayer.stop()
            activePlayer.setMediaItem(currentItem, 0)
            activePlayer.prepare()
            activePlayer.play()
        }, delayMs)
    }

    private fun scheduleBufferingRecovery() {
        cancelBufferingRecovery()
        bufferingRecoveryRunnable = Runnable {
            val exoPlayer = player ?: return@Runnable
            if (exoPlayer.playbackState == Player.STATE_BUFFERING && exoPlayer.playWhenReady) {
                Log.w("SwiftRadio", "Svc buffering timeout; retrying current stream")
                retryCurrentItem("buffer_timeout")
            }
        }.also {
            mainHandler.postDelayed(it, 15_000L)
        }
    }

    private fun startStallRecovery() {
        val exoPlayer = player ?: return
        lastObservedPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        lastPositionRealtimeMs = SystemClock.elapsedRealtime()
        scheduleStallCheck()
    }

    private fun scheduleStallCheck() {
        cancelStallRecovery()
        stallRecoveryRunnable = Runnable {
            val exoPlayer = player ?: return@Runnable
            if (!exoPlayer.playWhenReady || exoPlayer.playbackState != Player.STATE_READY) return@Runnable

            val nowRealtime = SystemClock.elapsedRealtime()
            val currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            val positionAdvanced = currentPosition > lastObservedPositionMs + 250L
            if (positionAdvanced) {
                lastObservedPositionMs = currentPosition
                lastPositionRealtimeMs = nowRealtime
                scheduleStallCheck()
                return@Runnable
            }

            val stagnantForMs = nowRealtime - lastPositionRealtimeMs
            if (stagnantForMs >= 15_000L) {
                Log.w(
                    "SwiftRadio",
                    "Svc playback stall detected at position=$currentPosition; retrying stream"
                )
                retryCurrentItem("stall_ready")
                return@Runnable
            }

            scheduleStallCheck()
        }.also {
            mainHandler.postDelayed(it, 5_000L)
        }
    }

    private fun cancelBufferingRecovery() {
        val runnable = bufferingRecoveryRunnable ?: return
        mainHandler.removeCallbacks(runnable)
        bufferingRecoveryRunnable = null
    }

    private fun cancelStallRecovery() {
        val runnable = stallRecoveryRunnable ?: return
        mainHandler.removeCallbacks(runnable)
        stallRecoveryRunnable = null
    }

    private fun logAudioOutputState(tag: String) {
        val exoPlayer = player ?: return
        val musicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val musicMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        Log.d(
            "SwiftRadio",
            "Svc audio $tag playerVolume=${exoPlayer.volume} deviceMusicVolume=$musicVolume/$musicMax"
        )
    }
}
