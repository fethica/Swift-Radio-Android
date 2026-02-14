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
                                trackTitle = trackTitle,
                                artistName = artistName,
                                artworkUrl = resolvedArtwork,
                                isPlaying = isPlaying,
                                isLive = true,
                                onPlayPauseClick = { togglePlayPause() },
                                onNextClick = { nextStation() },
                                onPreviousClick = { previousStation() },
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
                }

                override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                    trackTitle = metadata.title?.toString() ?: ""
                    artistName = metadata.artist?.toString() ?: ""
                    artworkUrl = metadata.artworkUri?.toString()
                }
            })
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        super.onStop()
        MediaController.releaseFuture(controllerFuture)
    }

    private fun playStation(station: RadioStation) {
        currentStation = station
        trackTitle = ""
        artistName = ""
        artworkUrl = null
        controllerFuture.addListener({
            val controller = controllerFuture.get()
            val mediaItem = MediaItem.fromUri(station.streamURL)
            controller.setMediaItem(mediaItem)
            controller.prepare()
            controller.play()
        }, MoreExecutors.directExecutor())
    }

    private fun togglePlayPause() {
        controllerFuture.addListener({
            val controller = controllerFuture.get()
            if (controller.isPlaying) controller.pause() else controller.play()
        }, MoreExecutors.directExecutor())
    }

    private fun nextStation() {
        val idx = stations.indexOf(currentStation)
        if (idx >= 0 && stations.isNotEmpty()) {
            playStation(stations[(idx + 1) % stations.size])
        }
    }

    private fun previousStation() {
        val idx = stations.indexOf(currentStation)
        if (idx >= 0 && stations.isNotEmpty()) {
            playStation(stations[(idx - 1 + stations.size) % stations.size])
        }
    }

    private fun resolveStationImageUrl(station: RadioStation?): String? {
        station ?: return null
        if (station.imageURL.startsWith("http")) return station.imageURL
        val extensions = listOf("png", "jpg", "jpeg")
        val assetFiles = assets.list("")?.toSet() ?: emptySet()
        val match = extensions.firstOrNull { "${station.imageURL}.$it" in assetFiles }
        return if (match != null) "file:///android_asset/${station.imageURL}.$match" else null
    }
}
