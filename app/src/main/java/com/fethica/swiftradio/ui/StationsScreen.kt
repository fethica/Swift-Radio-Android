package com.fethica.swiftradio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.fethica.swiftradio.data.RadioStation
import com.fethica.swiftradio.ui.components.EqualizerAnimation
import com.fethica.swiftradio.ui.theme.SubtitleGray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationsScreen(
    stations: List<RadioStation>,
    currentStation: RadioStation?,
    isPlaying: Boolean,
    showMiniPlayer: Boolean,
    onStationClick: (RadioStation) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Swift Radio") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = if (showMiniPlayer) 80.dp else 0.dp)
        ) {
            items(stations) { station ->
                StationRow(
                    station = station,
                    isCurrentStation = station == currentStation,
                    isPlaying = isPlaying && station == currentStation,
                    onClick = { onStationClick(station) }
                )
            }
        }
    }
}

@Composable
private fun StationRow(
    station: RadioStation,
    isCurrentStation: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentStation) {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val context = LocalContext.current
            val imageModel = if (station.imageURL.startsWith("http")) {
                station.imageURL
            } else {
                // Try common extensions for local assets
                val extensions = listOf("png", "jpg", "jpeg")
                val assetFiles = context.assets.list("")?.toSet() ?: emptySet()
                val match = extensions.firstOrNull { "${station.imageURL}.$it" in assetFiles }
                if (match != null) {
                    "file:///android_asset/${station.imageURL}.$match"
                } else {
                    "file:///android_asset/stationImage.png"
                }
            }

            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageModel)
                    .crossfade(true)
                    .build(),
                contentDescription = station.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(70.dp)
                    .clip(RoundedCornerShape(12.dp))
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = station.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isCurrentStation) {
                        EqualizerAnimation(
                            isAnimating = isPlaying,
                            barColor = SubtitleGray,
                            modifier = Modifier.height(12.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = station.desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SubtitleGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
