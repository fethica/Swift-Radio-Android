package com.fethica.swiftradio

import android.Manifest
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fethica.swiftradio.ui.NowPlayingScreen
import com.fethica.swiftradio.ui.StationsScreen
import com.fethica.swiftradio.ui.components.MiniPlayer
import com.fethica.swiftradio.ui.theme.SwiftRadioTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

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

        setContent {
            val vm: PlayerViewModel = viewModel()
            val state = vm.uiState
            val lifecycleOwner = LocalLifecycleOwner.current

            DisposableEffect(lifecycleOwner) {
                vm.connectController()
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> vm.connectController()
                        Lifecycle.Event.ON_DESTROY -> vm.disconnectController()
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    vm.disconnectController()
                }
            }

            SwiftRadioTheme {
                val scope = rememberCoroutineScope()
                val bottomSheetState = rememberStandardBottomSheetState(
                    initialValue = SheetValue.Hidden,
                    skipHiddenState = false
                )
                val scaffoldState = rememberBottomSheetScaffoldState(
                    bottomSheetState = bottomSheetState
                )

                BottomSheetScaffold(
                    scaffoldState = scaffoldState,
                    sheetPeekHeight = 0.dp,
                    sheetDragHandle = null,
                    sheetContainerColor = MaterialTheme.colorScheme.surface,
                    sheetContent = {
                        if (state.currentStation != null) {
                            NowPlayingScreen(
                                stationName = state.currentStation?.name ?: "",
                                stationDesc = state.currentStation?.desc ?: "",
                                trackTitle = state.trackTitle,
                                artistName = state.artistName,
                                artworkUrl = vm.resolvedArtwork,
                                isPlaying = state.isPlaying,
                                isLive = state.isLive,
                                currentPositionMs = state.currentPositionMs,
                                durationMs = state.durationMs,
                                onPlayPauseClick = { vm.togglePlayPause() },
                                onNextClick = { vm.nextStation() },
                                onPreviousClick = { vm.previousStation() },
                                onSeek = { vm.seekTo(it) },
                                hideNextPrevious = Config.hideNextPreviousButtons
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) { padding ->
                    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                        StationsScreen(
                            stations = state.stations,
                            currentStation = state.currentStation,
                            isPlaying = state.isPlaying,
                            showMiniPlayer = state.currentStation != null,
                            onStationClick = { station ->
                                vm.playStation(station)
                                scope.launch {
                                    bottomSheetState.partialExpand()
                                }
                            }
                        )

                        // Mini player overlay at the bottom
                        if (state.currentStation != null) {
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
                                    stationName = state.currentStation?.name ?: "",
                                    trackTitle = state.trackTitle,
                                    artistName = state.artistName,
                                    artworkUrl = vm.resolvedArtwork,
                                    isPlaying = state.isPlaying,
                                    onPlayPauseClick = { vm.togglePlayPause() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
