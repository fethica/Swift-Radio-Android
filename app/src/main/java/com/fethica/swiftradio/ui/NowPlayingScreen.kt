package com.fethica.swiftradio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.fethica.swiftradio.R
import com.fethica.swiftradio.ui.components.StationInfoSheet
import com.fethica.swiftradio.ui.theme.SubtitleGray

@Composable
fun NowPlayingScreen(
    stationName: String,
    stationDesc: String,
    stationLongDesc: String = "",
    stationWebsite: String = "",
    trackTitle: String,
    artistName: String,
    artworkUrl: String?,
    isPlaying: Boolean,
    isLive: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onSeek: (Long) -> Unit = {},
    hideNextPrevious: Boolean = false
) {
    var showInfoSheet by remember { mutableStateOf(false) }
    val normalizedTitle = trackTitle.trim()
    val normalizedArtist = artistName.trim()
    val hasTrackMetadata = normalizedTitle.isNotBlank() && !normalizedTitle.equals(stationName, ignoreCase = true)
    val displayTitle = if (hasTrackMetadata) {
        if (normalizedArtist.isNotBlank()) {
            "$normalizedTitle — $normalizedArtist"
        } else {
            normalizedTitle
        }
    } else {
        stationName
    }
    val displaySubtitle = when {
        hasTrackMetadata -> stationName
        else -> stationDesc
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Blurred background artwork
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(artworkUrl)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(60.dp)
        )

        // Dark overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
        )

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Drag handle
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.3f))
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Artwork
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(artworkUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = stationName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Track info — fixed min heights to prevent layout jumps
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 32.dp)
                    .basicMarquee()
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = displaySubtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = SubtitleGray,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 24.dp)
                    .basicMarquee()
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Scrubber / LIVE area — fixed height to prevent layout jumps
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isLive) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        HorizontalDivider(
                            color = Color.White.copy(alpha = 0.2f),
                            thickness = 1.dp
                        )
                        Text(
                            text = "LIVE",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF2A2A2A))
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                } else if (durationMs > 0) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val fraction = if (durationMs > 0) {
                            (currentPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                        } else 0f
                        ScrubBar(
                            fraction = fraction,
                            onSeek = { newFraction ->
                                onSeek((newFraction * durationMs).toLong())
                            }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatTime(currentPositionMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = SubtitleGray
                            )
                            Text(
                                text = "-${formatTime(durationMs - currentPositionMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = SubtitleGray
                            )
                        }
                    }
                }
            }

            // Center controls in remaining space
            Spacer(modifier = Modifier.weight(1f))

            // Playback controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!hideNextPrevious) {
                    IconButton(onClick = onPreviousClick, modifier = Modifier.size(48.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_previous),
                            contentDescription = "Previous",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.size(32.dp))

                IconButton(
                    onClick = onPlayPauseClick,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f))
                ) {
                    Icon(
                        painter = painterResource(
                            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        ),
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.size(32.dp))

                if (!hideNextPrevious) {
                    IconButton(onClick = onNextClick, modifier = Modifier.size(48.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_next),
                            contentDescription = "Next",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Bottom row — icons centered together
            Row(
                modifier = Modifier.padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { /* TODO: media output switcher */ }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_audio_output),
                        contentDescription = "Audio output",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(24.dp))

                IconButton(onClick = { showInfoSheet = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more),
                        contentDescription = "More options",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        if (showInfoSheet) {
            StationInfoSheet(
                stationName = stationName,
                stationDesc = stationDesc,
                longDesc = stationLongDesc,
                website = stationWebsite,
                trackTitle = trackTitle,
                artistName = artistName,
                onDismiss = { showInfoSheet = false }
            )
        }
    }
}

@Composable
private fun ScrubBar(
    fraction: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackHeight = 4.dp
    val thumbRadius = 6.dp
    val totalHeight = thumbRadius * 2

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onSeek((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val trackY = size.height / 2
            val trackHeightPx = trackHeight.toPx()
            val cornerRadius = CornerRadius(trackHeightPx / 2)

            // Inactive track
            drawRoundRect(
                color = Color.White.copy(alpha = 0.3f),
                topLeft = androidx.compose.ui.geometry.Offset(0f, trackY - trackHeightPx / 2),
                size = Size(size.width, trackHeightPx),
                cornerRadius = cornerRadius
            )

            // Active track
            val activeWidth = size.width * fraction
            if (activeWidth > 0f) {
                drawRoundRect(
                    color = Color.White,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, trackY - trackHeightPx / 2),
                    size = Size(activeWidth, trackHeightPx),
                    cornerRadius = cornerRadius
                )
            }

            // Thumb
            val thumbX = activeWidth.coerceIn(thumbRadius.toPx(), size.width - thumbRadius.toPx())
            drawCircle(
                color = Color.White,
                radius = thumbRadius.toPx(),
                center = androidx.compose.ui.geometry.Offset(thumbX, trackY)
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
