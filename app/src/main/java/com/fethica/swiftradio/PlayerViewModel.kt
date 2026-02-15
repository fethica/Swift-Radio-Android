package com.fethica.swiftradio

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.fethica.swiftradio.data.ArtworkService
import com.fethica.swiftradio.data.RadioStation
import com.fethica.swiftradio.data.StationsRepository
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val stations: List<RadioStation> = emptyList(),
    val currentStation: RadioStation? = null,
    val isPlaying: Boolean = false,
    val trackTitle: String = "",
    val artistName: String = "",
    val artworkUrl: String? = null,
    val isLive: Boolean = true,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L
)

private data class RawPlaybackState(
    val isPlaying: Boolean = false,
    val currentMediaItemIndex: Int = -1,
    val metadata: MediaMetadata = MediaMetadata.Builder().build(),
    val isLive: Boolean = true,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    var uiState by mutableStateOf(PlayerUiState())
        private set

    val resolvedArtwork: String?
        get() = uiState.artworkUrl ?: resolveStationImageUrl(uiState.currentStation)

    // Controller
    private val mainExecutor = ContextCompat.getMainExecutor(application)
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var controller: MediaController? = null
    private var isConnecting = false
    private val pendingCommands = mutableListOf<(MediaController) -> Unit>()

    // StateFlow bridge: Player.Listener → flow → collect → uiState
    // (updating mutableStateOf directly from Player.Listener callbacks doesn't trigger recomposition)
    private val _rawState = MutableStateFlow(RawPlaybackState())

    // Playlist tracking
    private var stationMediaItems: List<MediaItem> = emptyList()
    private var activeStationIndex: Int = -1

    // Metadata/artwork tracking
    private val artworkService = ArtworkService()
    private var hasUserSelectedStation = false
    private var lastMetadataKey = ""
    private var currentArtworkLookupTerm = ""
    private var artworkLookupJob: Job? = null
    private var lastObservedMediaItemIndex = -1
    private var positionPollingJob: Job? = null
    private var lastKnownMetadata: MediaMetadata = MediaMetadata.Builder().build()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            controller?.let {
                publishState(it)
                updatePositionPolling(it)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            controller?.let {
                lastKnownMetadata = mediaItem?.mediaMetadata ?: MediaMetadata.Builder().build()
                publishState(it, lastKnownMetadata)
            }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            lastKnownMetadata = mediaMetadata
            controller?.let { publishState(it, mediaMetadata) }
        }

        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) {
            if (isEmptyTrackMetadata(mediaMetadata)) {
                val baseMetadata = controller?.currentMediaItem?.mediaMetadata ?: lastKnownMetadata
                if (!metadataEquivalent(baseMetadata, lastKnownMetadata)) {
                    lastKnownMetadata = baseMetadata
                    controller?.let { publishState(it, baseMetadata) }
                }
                return
            }
            val merged = mergeTrackMetadata(lastKnownMetadata, mediaMetadata)
            if (metadataEquivalent(merged, lastKnownMetadata)) return
            lastKnownMetadata = merged
            controller?.let { publishState(it, merged) }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            controller?.let { publishState(it) }
        }
    }

    init {
        observeRawState()
        loadStations()
    }

    // --- Public API ---

    fun connectController() {
        if (controller != null || isConnecting) return
        isConnecting = true
        val sessionToken = SessionToken(
            getApplication(),
            ComponentName(getApplication(), AudioService::class.java)
        )
        controllerFuture = MediaController.Builder(getApplication(), sessionToken).buildAsync()
        controllerFuture.addListener({
            try {
                val ctrl = controllerFuture.get()
                controller = ctrl
                ctrl.addListener(playerListener)
                lastKnownMetadata = mergeTrackMetadata(ctrl.mediaMetadata, ctrl.playlistMetadata)
                publishState(ctrl)
                updatePositionPolling(ctrl)
                flushPendingCommands(ctrl)
            } catch (e: Exception) {
                Log.e("SwiftRadio", "Controller connect failed", e)
            } finally {
                isConnecting = false
            }
        }, mainExecutor)
    }

    fun disconnectController() {
        positionPollingJob?.cancel()
        isConnecting = false
        controller?.removeListener(playerListener)
        controller = null
        pendingCommands.clear()
        if (::controllerFuture.isInitialized) {
            MediaController.releaseFuture(controllerFuture)
        }
        lastKnownMetadata = MediaMetadata.Builder().build()
        _rawState.value = RawPlaybackState()
    }

    fun playStation(station: RadioStation) {
        if (uiState.stations.isEmpty()) return
        val stationIndex = uiState.stations.indexOf(station)
        if (stationIndex < 0) return

        hasUserSelectedStation = true
        resetTrackInfo()
        uiState = uiState.copy(currentStation = station)
        stationMediaItems = buildMediaItems(uiState.stations)
        activeStationIndex = stationIndex
        withController { ctrl -> playActiveStation(ctrl) }
    }

    fun togglePlayPause() {
        withController { ctrl ->
            if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
            publishState(ctrl)
        }
    }

    fun nextStation() {
        if (uiState.stations.isEmpty() || stationMediaItems.isEmpty()) return
        hasUserSelectedStation = true
        activeStationIndex = (activeStationIndex + 1) % stationMediaItems.size
        resetTrackInfo()
        uiState = uiState.copy(currentStation = uiState.stations.getOrNull(activeStationIndex))
        withController { ctrl -> playActiveStation(ctrl) }
    }

    fun previousStation() {
        if (uiState.stations.isEmpty() || stationMediaItems.isEmpty()) return
        hasUserSelectedStation = true
        activeStationIndex = (activeStationIndex - 1 + stationMediaItems.size) % stationMediaItems.size
        resetTrackInfo()
        uiState = uiState.copy(currentStation = uiState.stations.getOrNull(activeStationIndex))
        withController { ctrl -> playActiveStation(ctrl) }
    }

    fun seekTo(positionMs: Long) {
        withController { ctrl ->
            ctrl.seekTo(positionMs)
            publishState(ctrl)
        }
    }

    override fun onCleared() {
        artworkLookupJob?.cancel()
        disconnectController()
        super.onCleared()
    }

    // --- Controller helpers ---

    private fun withController(command: (MediaController) -> Unit) {
        val ctrl = controller
        if (ctrl != null) {
            command(ctrl)
            return
        }
        pendingCommands.add(command)
        connectController()
    }

    private fun flushPendingCommands(controller: MediaController) {
        if (pendingCommands.isEmpty()) return
        val commands = pendingCommands.toList()
        pendingCommands.clear()
        commands.forEach { it(controller) }
    }

    private fun playActiveStation(controller: MediaController) {
        val index = activeStationIndex
        if (index !in stationMediaItems.indices) return
        lastKnownMetadata = stationMediaItems[index].mediaMetadata
        controller.setMediaItems(stationMediaItems, index, 0)
        controller.prepare()
        controller.play()
        publishState(controller, lastKnownMetadata)
    }

    private fun publishState(controller: MediaController, metadataOverride: MediaMetadata? = null) {
        val metadata = metadataOverride ?: lastKnownMetadata
        lastKnownMetadata = metadata
        val currentIndex = if (activeStationIndex in stationMediaItems.indices) {
            activeStationIndex
        } else {
            controller.currentMediaItemIndex
        }
        _rawState.value = RawPlaybackState(
            isPlaying = controller.isPlaying,
            currentMediaItemIndex = currentIndex,
            metadata = metadata,
            isLive = controller.isCurrentMediaItemLive,
            currentPositionMs = controller.currentPosition.coerceAtLeast(0),
            durationMs = controller.duration.coerceAtLeast(0)
        )
    }

    private fun updatePositionPolling(controller: MediaController) {
        positionPollingJob?.cancel()
        if (!controller.isPlaying) return
        positionPollingJob = viewModelScope.launch {
            while (isActive) {
                val active = this@PlayerViewModel.controller ?: break
                if (active !== controller || !active.isPlaying) break
                publishState(active)
                delay(500)
            }
        }
    }

    // --- State observation ---

    private fun observeRawState() {
        viewModelScope.launch {
            _rawState.collect { raw ->
                if (hasUserSelectedStation &&
                    raw.currentMediaItemIndex >= 0 &&
                    raw.currentMediaItemIndex != lastObservedMediaItemIndex
                ) {
                    resetTrackInfo()
                }
                lastObservedMediaItemIndex = raw.currentMediaItemIndex

                val station = resolveStation(raw.currentMediaItemIndex)
                uiState = uiState.copy(
                    currentStation = station,
                    isPlaying = raw.isPlaying,
                    isLive = raw.isLive,
                    currentPositionMs = raw.currentPositionMs,
                    durationMs = raw.durationMs
                )

                val metadataKey = metadataFingerprint(raw.metadata)
                if (metadataKey != lastMetadataKey) {
                    lastMetadataKey = metadataKey
                    applyMetadata(raw.metadata, station)
                }
            }
        }
    }

    private fun resolveStation(index: Int): RadioStation? {
        if (!hasUserSelectedStation) return uiState.currentStation
        return uiState.stations.getOrNull(index) ?: uiState.currentStation
    }

    // --- Metadata processing ---

    private fun applyMetadata(metadata: MediaMetadata, station: RadioStation?) {
        var newTitle = firstNonBlank(
            metadata.displayTitle?.toString().orEmpty().trim(),
            metadata.title?.toString().orEmpty().trim(),
            metadata.description?.toString().orEmpty().trim(),
            metadata.subtitle?.toString().orEmpty().trim()
        )
        var newArtist = firstNonBlank(
            metadata.artist?.toString().orEmpty().trim(),
            metadata.albumArtist?.toString().orEmpty().trim(),
            metadata.subtitle?.toString().orEmpty().trim()
        )

        if (newArtist.isBlank()) {
            splitArtistTitle(newTitle)?.let { (artist, title) ->
                newArtist = artist
                newTitle = title
            }
        }
        if (newTitle.isBlank()) {
            splitArtistTitle(newArtist)?.let { (artist, title) ->
                newArtist = artist
                newTitle = title
            }
        }

        val stationName = station?.name?.trim().orEmpty()
        val stationDesc = station?.desc?.trim().orEmpty()
        val hasTrackMetadata =
            if (station != null) {
                (newTitle.isNotBlank() && !newTitle.equals(stationName, ignoreCase = true)) ||
                    (newArtist.isNotBlank() && !newArtist.equals(stationDesc, ignoreCase = true))
            } else {
                newTitle.isNotBlank() || newArtist.isNotBlank()
            }

        uiState = uiState.copy(trackTitle = newTitle, artistName = newArtist)

        val streamArtwork = metadata.artworkUri?.toString()?.takeIf { it.isNotBlank() }
        val previousArtwork = uiState.artworkUrl
        val stationArtwork = resolveStationImageUrl(station)
        val streamArtworkIsStationArtwork =
            streamArtwork != null && stationArtwork != null && streamArtwork == stationArtwork

        if (streamArtwork != null) {
            uiState = uiState.copy(artworkUrl = streamArtwork)
        }

        if (streamArtworkIsStationArtwork) {
            uiState = uiState.copy(artworkUrl = null)
        }

        if (!hasTrackMetadata) {
            currentArtworkLookupTerm = ""
            artworkLookupJob?.cancel()
            if (streamArtwork == null || streamArtworkIsStationArtwork) {
                uiState = uiState.copy(artworkUrl = null)
            }
            return
        }

        val lookupTerm = listOf(newArtist, newTitle)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (lookupTerm.isBlank()) {
            currentArtworkLookupTerm = ""
            artworkLookupJob?.cancel()
            return
        }
        if (lookupTerm == currentArtworkLookupTerm) return

        val streamArtworkLooksFresh =
            streamArtwork != null &&
                !streamArtworkIsStationArtwork &&
                streamArtwork != previousArtwork
        if (streamArtworkLooksFresh) {
            currentArtworkLookupTerm = lookupTerm
            artworkLookupJob?.cancel()
            return
        }

        currentArtworkLookupTerm = lookupTerm
        artworkLookupJob?.cancel()
        artworkLookupJob = viewModelScope.launch {
            val artworkUrl = artworkService.fetchArtworkUrl(lookupTerm)
            if (artworkUrl != null && lookupTerm == currentArtworkLookupTerm) {
                uiState = uiState.copy(artworkUrl = artworkUrl)
            }
        }
    }

    // --- Helpers ---

    private fun resetTrackInfo() {
        lastMetadataKey = ""
        currentArtworkLookupTerm = ""
        artworkLookupJob?.cancel()
        uiState = uiState.copy(trackTitle = "", artistName = "", artworkUrl = null)
    }

    private fun loadStations() {
        val repository = StationsRepository(getApplication())
        viewModelScope.launch {
            val remoteUrl = if (Config.useLocalStations) null else Config.stationsURL
            uiState = uiState.copy(stations = repository.loadStations(remoteUrl))
        }
    }

    private fun buildMediaItems(stations: List<RadioStation>): List<MediaItem> {
        return stations.map { station ->
            val imageUri = resolveStationImageUrl(station)
            MediaItem.Builder()
                .setUri(station.streamURL)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(station.name)
                        .setArtist(station.desc)
                        .setArtworkUri(imageUri?.let { Uri.parse(it) })
                        .build()
                )
                .build()
        }
    }

    private fun resolveStationImageUrl(station: RadioStation?): String? {
        station ?: return null
        if (station.imageURL.startsWith("http")) return station.imageURL
        if (station.imageURL.isNotBlank()) {
            val extensions = listOf("png", "jpg", "jpeg")
            val assetFiles = getApplication<Application>().assets.list("")?.toSet() ?: emptySet()
            val match = extensions.firstOrNull { "${station.imageURL}.$it" in assetFiles }
            if (match != null) return "file:///android_asset/${station.imageURL}.$match"
        }
        return "file:///android_asset/stationImage.png"
    }

    private fun metadataFingerprint(metadata: MediaMetadata): String {
        return listOf(
            metadata.displayTitle?.toString().orEmpty(),
            metadata.title?.toString().orEmpty(),
            metadata.artist?.toString().orEmpty(),
            metadata.albumArtist?.toString().orEmpty(),
            metadata.subtitle?.toString().orEmpty(),
            metadata.description?.toString().orEmpty(),
            metadata.artworkUri?.toString().orEmpty()
        ).joinToString("|")
    }

    private fun metadataEquivalent(left: MediaMetadata, right: MediaMetadata): Boolean {
        return left.title?.toString().orEmpty() == right.title?.toString().orEmpty() &&
            left.artist?.toString().orEmpty() == right.artist?.toString().orEmpty() &&
            left.artworkUri?.toString().orEmpty() == right.artworkUri?.toString().orEmpty()
    }

    private fun mergeTrackMetadata(base: MediaMetadata, playlistMetadata: MediaMetadata?): MediaMetadata {
        if (playlistMetadata == null) return base
        val title = playlistMetadata.title?.toString().orEmpty().trim()
        val artist = playlistMetadata.artist?.toString().orEmpty().trim()
        if (title.isBlank() && artist.isBlank()) return base
        val builder = base.buildUpon()
        if (title.isNotBlank()) builder.setTitle(title)
        if (artist.isNotBlank()) {
            builder.setArtist(artist)
        } else if (title.isNotBlank()) {
            builder.setArtist(null)
        }
        return builder.build()
    }

    private fun isEmptyTrackMetadata(metadata: MediaMetadata): Boolean {
        return metadata.title.isNullOrBlank() && metadata.artist.isNullOrBlank()
    }

    private fun firstNonBlank(vararg values: String): String {
        return values.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun splitArtistTitle(value: String): Pair<String, String>? {
        if (value.isBlank()) return null
        val separators = listOf(" - ", " – ", " — ")
        separators.forEach { separator ->
            val index = value.indexOf(separator)
            if (index > 0 && index < value.length - separator.length) {
                val artist = value.substring(0, index).trim()
                val title = value.substring(index + separator.length).trim()
                if (artist.isNotBlank() && title.isNotBlank()) return artist to title
            }
        }
        return null
    }
}
