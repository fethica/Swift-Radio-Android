package com.fethica.swiftradio

import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.fethica.swiftradio.data.RadioStation
import com.fethica.swiftradio.data.StationsRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var player: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var bufferingRecoveryRunnable: Runnable? = null
    private var stallRecoveryRunnable: Runnable? = null
    private var retryCount = 0
    private var lastObservedPositionMs: Long = 0L
    private var lastPositionRealtimeMs: Long = 0L
    private val audioManager: AudioManager by lazy {
        getSystemService(AudioManager::class.java)
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main)
    private var stations: List<RadioStation> = emptyList()
    private var browseMediaItems: List<MediaItem> = emptyList()
    private val stationsLoaded = CompletableDeferred<Unit>()

    companion object {
        private const val ROOT_ID = "root"
        private const val STATIONS_ID = "stations"
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

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val exoPlayer = player ?: return
            Log.d(
                "SwiftRadio",
                "Svc media item transition reason=$reason uri='${mediaItem?.localConfiguration?.uri}'"
            )
            cancelRetry()
            retryCount = 0
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

    private val libraryCallback = object : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .setTitle("Swift Radio")
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != ROOT_ID && parentId != STATIONS_ID) {
                return Futures.immediateFuture(
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                )
            }
            return Futures.immediateFuture(
                LibraryResult.ofItemList(browseMediaItems, params)
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = browseMediaItems.firstOrNull { it.mediaId == mediaId }
                ?: return Futures.immediateFuture(
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                )
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            // Items from the phone app controller already have URIs — pass them through
            val allHaveUri = mediaItems.all { it.localConfiguration?.uri != null }
            if (allHaveUri) {
                return Futures.immediateFuture(mediaItems)
            }

            // Android Auto sends items by mediaId only — wait for stations to load, then resolve
            val future = SettableFuture.create<List<MediaItem>>()
            serviceScope.launch {
                try {
                    stationsLoaded.await()
                    future.set(resolveAutoMediaItems(mediaItems))
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
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
        mediaSession = MediaLibrarySession.Builder(this, exoPlayer, libraryCallback).build()

        loadStationsForBrowse()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        player?.removeListener(playerListener)
        cancelRetry()
        cancelBufferingRecovery()
        cancelStallRecovery()
        mainHandler.removeCallbacksAndMessages(null)
        player = null
        mediaSession = null
        super.onDestroy()
    }

    private fun loadStationsForBrowse() {
        val repository = StationsRepository(this)
        serviceScope.launch {
            try {
                val remoteUrl = if (Config.useLocalStations) null else Config.stationsURL
                stations = withContext(Dispatchers.IO) { repository.loadStations(remoteUrl) }
                browseMediaItems = stations.mapIndexed { index, station ->
                    val imageUrl = resolveStationImageUrl(station)
                    MediaItem.Builder()
                        .setMediaId("station_$index")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(station.name)
                                .setArtist(station.desc)
                                .setArtworkUri(imageUrl?.let { Uri.parse(it) })
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                .build()
                        )
                        .build()
                }
            } catch (e: Exception) {
                Log.e("SwiftRadio", "Failed to load stations for browse", e)
            }
            stationsLoaded.complete(Unit)
            mediaSession?.let { session ->
                session.connectedControllers.forEach { controller ->
                    session.notifyChildrenChanged(controller, ROOT_ID, browseMediaItems.size, null)
                }
            }
        }
    }

    private fun resolveAutoMediaItems(mediaItems: List<MediaItem>): List<MediaItem> {
        val requestedId = mediaItems.firstOrNull()?.mediaId
        val selectedIndex = browseMediaItems.indexOfFirst { it.mediaId == requestedId }
            .takeIf { it >= 0 } ?: 0

        val playableItems = stations.mapIndexed { index, station ->
            val imageUrl = resolveStationImageUrl(station)
            MediaItem.Builder()
                .setMediaId("station_$index")
                .setUri(station.streamURL)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(station.name)
                        .setArtist(station.desc)
                        .setArtworkUri(imageUrl?.let { Uri.parse(it) })
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }

        val reordered = playableItems.subList(selectedIndex, playableItems.size) +
            playableItems.subList(0, selectedIndex)
        return reordered
    }

    private fun resolveStationImageUrl(station: RadioStation): String? {
        if (station.imageURL.startsWith("http")) return station.imageURL
        if (station.imageURL.isNotBlank()) {
            val extensions = listOf("png", "jpg", "jpeg")
            val assetFiles = assets.list("")?.toSet() ?: emptySet()
            val match = extensions.firstOrNull { "${station.imageURL}.$it" in assetFiles }
            if (match != null) return "file:///android_asset/${station.imageURL}.$match"
        }
        return "file:///android_asset/stationImage.png"
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
        val currentUri = exoPlayer.currentMediaItem?.localConfiguration?.uri
        Log.w(
            "SwiftRadio",
            "Svc retry stream reason=$reason attempt=$retryCount delayMs=$delayMs index=$currentIndex uri='$currentUri'"
        )

        cancelRetry()
        retryRunnable = Runnable {
            val activePlayer = player ?: return@Runnable
            if (activePlayer.mediaItemCount <= 0) return@Runnable
            val index = activePlayer.currentMediaItemIndex.coerceIn(0, activePlayer.mediaItemCount - 1)
            // Re-prepare current item without replacing the playlist.
            activePlayer.stop()
            activePlayer.seekTo(index, 0)
            activePlayer.prepare()
            activePlayer.play()
        }.also {
            mainHandler.postDelayed(it, delayMs)
        }
    }

    private fun cancelRetry() {
        val runnable = retryRunnable ?: return
        mainHandler.removeCallbacks(runnable)
        retryRunnable = null
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
