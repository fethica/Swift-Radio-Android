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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.fethica.swiftradio.R
import com.fethica.swiftradio.ui.theme.SubtitleGray

@Composable
fun NowPlayingScreen(
    stationName: String,
    stationDesc: String,
    trackTitle: String,
    artistName: String,
    artworkUrl: String?,
    isPlaying: Boolean,
    isLive: Boolean,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onMoreClick: () -> Unit = {},
    hideNextPrevious: Boolean = false
) {
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
                text = trackTitle.ifBlank { stationName },
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 32.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = if (trackTitle.isNotBlank()) stationName else stationDesc,
                style = MaterialTheme.typography.bodyLarge,
                color = SubtitleGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 24.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Divider area — fixed height so layout doesn't jump
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isLive) {
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

                IconButton(onClick = onMoreClick) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more),
                        contentDescription = "More options",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
