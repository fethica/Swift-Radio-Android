package com.fethica.swiftradio

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.fethica.swiftradio.data.RadioStation
import com.fethica.swiftradio.data.StationsRepository
import com.fethica.swiftradio.ui.NowPlayingScreen
import com.fethica.swiftradio.ui.StationsScreen
import com.fethica.swiftradio.ui.components.MiniPlayer
import com.fethica.swiftradio.ui.theme.SwiftRadioTheme
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var stations by mutableStateOf<List<RadioStation>>(emptyList())
    private var currentStation by mutableStateOf<RadioStation?>(null)
    private var isPlaying by mutableStateOf(false)
    private var trackTitle by mutableStateOf("")
    private var artistName by mutableStateOf("")
    private var artworkUrl by mutableStateOf<String?>(null)
    private var isLive by mutableStateOf(true)
    private var currentPositionMs by mutableStateOf(0L)
    private var durationMs by mutableStateOf(0L)
    private var positionPollingJob: kotlinx.coroutines.Job? = null
    private var artworkLookupJob: kotlinx.coroutines.Job? = null
    private val artworkService = com.fethica.swiftradio.data.ArtworkService()
    private var lastLookedUpTitle: String = ""

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        val repository = StationsRepository(this)
        lifecycleScope.launch {
            val remoteUrl = if (Config.useLocalStations) null else Config.stationsURL
            stations = repository.loadStations(remoteUrl)
        }

        setContent {
            SwiftRadioTheme {
                val scope = rememberCoroutineScope()
                val bottomSheetState = rememberStandardBottomSheetState(
                    initialValue = SheetValue.Hidden,
                    skipHiddenState = false
                )
                val scaffoldState = rememberBottomSheetScaffoldState(
                    bottomSheetState = bottomSheetState
                )

                val resolvedArtwork = artworkUrl ?: resolveStationImageUrl(currentStation)

                BottomSheetScaffold(
                    scaffoldState = scaffoldState,
                    sheetPeekHeight = 0.dp,
                    sheetDragHandle = null,
                    sheetContainerColor = MaterialTheme.colorScheme.surface,
                    sheetContent = {
                        if (currentStation != null) {
                            NowPlayingScreen(
                                stationName = currentStation?.name ?: "",
                                stationDesc = currentStation?.desc ?: "",
                                trackTitle = trackTitle,
                                artistName = artistName,
                                artworkUrl = resolvedArtwork,
                                isPlaying = isPlaying,
                                isLive = isLive,
                                currentPositionMs = currentPositionMs,
                                durationMs = durationMs,
                                onPlayPauseClick = { togglePlayPause() },
                                onNextClick = { nextStation() },
                                onPreviousClick = { previousStation() },
                                onSeek = { seekTo(it) },
                                hideNextPrevious = Config.hideNextPreviousButtons
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) { padding ->
                    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                        StationsScreen(
                            stations = stations,
                            currentStation = currentStation,
                            isPlaying = isPlaying,
                            showMiniPlayer = currentStation != null,
                            onStationClick = { station ->
                                playStation(station)
                                scope.launch {
                                    bottomSheetState.partialExpand()
                                }
                            }
                        )

                        // Mini player overlay at the bottom
                        if (currentStation != null) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .clickable {
                                        scope.launch { bottomSheetState.expand() }
                                    },
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface,
                                shadowElevation = 8.dp
                            ) {
                                MiniPlayer(
                                    stationName = currentStation?.name ?: "",
                                    trackTitle = trackTitle,
                                    artworkUrl = resolvedArtwork,
                                    isPlaying = isPlaying,
                                    onPlayPauseClick = { togglePlayPause() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, AudioService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            val controller = controllerFuture.get()
            isPlaying = controller.isPlaying
            controller.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                    updatePositionPolling(controller)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // Sync UI when station changes from notification next/previous
                    val index = controller.currentMediaItemIndex
                    if (index in stations.indices && stations[index] != currentStation) {
                        currentStation = stations[index]
                        trackTitle = ""
                        artistName = ""
                        artworkUrl = null
                        lastLookedUpTitle = ""
                        artworkLookupJob?.cancel()
                    }
                }

                override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                    val newTitle = metadata.title?.toString() ?: ""
                    val newArtist = metadata.artist?.toString() ?: ""
                    val streamArtwork = metadata.artworkUri?.toString()

                    trackTitle = newTitle
                    artistName = newArtist

                    // Use stream-provided artwork if available
                    if (streamArtwork != null) {
                        artworkUrl = streamArtwork
                        lastLookedUpTitle = ""
                        return
                    }

                    // Look up via iTunes if title changed and no stream artwork
                    if (newTitle.isNotBlank() && newTitle != lastLookedUpTitle) {
                        lastLookedUpTitle = newTitle
                        artworkLookupJob?.cancel()
                        artworkLookupJob = lifecycleScope.launch {
                            val url = artworkService.fetchArtworkUrl(newTitle)
                            if (url != null) {
                                artworkUrl = url
                                // Update notification artwork
                                updateNotificationMetadata(controller, newTitle, newArtist, url)
                            }
                        }
                    }
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    isLive = player.isCurrentMediaItemLive
                    durationMs = player.duration.coerceAtLeast(0)
                    currentPositionMs = player.currentPosition
                }
            })
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        super.onStop()
        positionPollingJob?.cancel()
        MediaController.releaseFuture(controllerFuture)
    }

    private fun updatePositionPolling(controller: MediaController) {
        positionPollingJob?.cancel()
        if (controller.isPlaying) {
            positionPollingJob = lifecycleScope.launch {
                while (true) {
                    currentPositionMs = controller.currentPosition
                    durationMs = controller.duration.coerceAtLeast(0)
                    kotlinx.coroutines.delay(500)
                }
            }
        }
    }

    private fun playStation(station: RadioStation) {
        currentStation = station
        trackTitle = ""
        artistName = ""
        artworkUrl = null
        lastLookedUpTitle = ""
        artworkLookupJob?.cancel()
        controllerFuture.addListener({
            val controller = controllerFuture.get()
            val stationIndex = stations.indexOf(station)

            // Build playlist from all stations with metadata
            val mediaItems = stations.map { s ->
                val imageUri = resolveStationImageUrl(s)
                MediaItem.Builder()
                    .setUri(s.streamURL)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(s.name)
                            .setArtist(s.desc)
                            .setArtworkUri(imageUri?.let { Uri.parse(it) })
                            .build()
                    )
                    .build()
            }

            // Only reset playlist if it changed (avoid resetting on station tap when already loaded)
            if (controller.mediaItemCount != mediaItems.size) {
                controller.setMediaItems(mediaItems, stationIndex, 0)
            } else {
                controller.seekTo(stationIndex, 0)
            }
            controller.prepare()
            controller.play()
        }, MoreExecutors.directExecutor())
    }

    private fun seekTo(positionMs: Long) {
        controllerFuture.addListener({
            val controller = controllerFuture.get()
            controller.seekTo(positionMs)
            currentPositionMs = positionMs
        }, MoreExecutors.directExecutor())
    }

    private fun togglePlayPause() {
        controllerFuture.addListener({
            val controller = controllerFuture.get()
            if (controller.isPlaying) controller.pause() else controller.play()
        }, MoreExecutors.directExecutor())
    }

    private fun nextStation() {
        controllerFuture.addListener({
            val controller = controllerFuture.get()
            val nextIndex = (controller.currentMediaItemIndex + 1) % stations.size
            controller.seekTo(nextIndex, 0)
            controller.prepare()
            controller.play()
            updateCurrentStationFromPlayer(controller)
        }, MoreExecutors.directExecutor())
    }

    private fun previousStation() {
        controllerFuture.addListener({
            val controller = controllerFuture.get()
            val prevIndex = (controller.currentMediaItemIndex - 1 + stations.size) % stations.size
            controller.seekTo(prevIndex, 0)
            controller.prepare()
            controller.play()
            updateCurrentStationFromPlayer(controller)
        }, MoreExecutors.directExecutor())
    }

    private fun updateNotificationMetadata(
        controller: MediaController,
        title: String,
        artist: String,
        artworkUrl: String
    ) {
        val index = controller.currentMediaItemIndex
        val currentItem = controller.getMediaItemAt(index)
        val updatedItem = currentItem.buildUpon()
            .setMediaMetadata(
                currentItem.mediaMetadata.buildUpon()
                    .setTitle(title)
                    .setArtist(artist.ifBlank { currentStation?.name })
                    .setArtworkUri(Uri.parse(artworkUrl))
                    .build()
            )
            .build()
        controller.replaceMediaItem(index, updatedItem)
    }

    private fun updateCurrentStationFromPlayer(controller: MediaController) {
        val index = controller.currentMediaItemIndex
        if (index in stations.indices) {
            currentStation = stations[index]
            trackTitle = ""
            artistName = ""
            artworkUrl = null
            lastLookedUpTitle = ""
            artworkLookupJob?.cancel()
        }
    }

    private fun resolveStationImageUrl(station: RadioStation?): String? {
        station ?: return null
        if (station.imageURL.startsWith("http")) return station.imageURL
        if (station.imageURL.isNotBlank()) {
            val extensions = listOf("png", "jpg", "jpeg")
            val assetFiles = assets.list("")?.toSet() ?: emptySet()
            val match = extensions.firstOrNull { "${station.imageURL}.$it" in assetFiles }
            if (match != null) return "file:///android_asset/${station.imageURL}.$match"
        }
        return "file:///android_asset/stationImage.png"
    }
}
