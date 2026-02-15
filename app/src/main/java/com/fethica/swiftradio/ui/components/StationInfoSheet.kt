package com.fethica.swiftradio.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationInfoSheet(
    stationName: String,
    stationDesc: String,
    longDesc: String,
    website: String,
    trackTitle: String,
    artistName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    val hasWebsite = website.isNotBlank() && website.startsWith("http")
    val hasLongDesc = longDesc.isNotBlank()
    val hasTrackInfo = trackTitle.isNotBlank() &&
        !trackTitle.equals(stationName, ignoreCase = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // About Station
            if (hasLongDesc) {
                SheetRow(
                    icon = Icons.Outlined.Info,
                    label = "About Station",
                    onClick = null
                )
                Text(
                    text = longDesc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 16.dp)
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }

            // Station Website
            if (hasWebsite) {
                SheetRow(
                    icon = Icons.Outlined.Language,
                    label = "Station Website",
                    onClick = { openUrl(context, website) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }

            // Search in Music App
            if (hasTrackInfo) {
                val searchQuery = buildSearchQuery(artistName, trackTitle)
                SheetRow(
                    icon = Icons.Outlined.MusicNote,
                    label = "Search in Music App",
                    onClick = { searchMusic(context, searchQuery) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }

            // Share Now Playing
            SheetRow(
                icon = Icons.Outlined.Share,
                label = "Share Now Playing",
                onClick = {
                    shareNowPlaying(context, stationName, trackTitle, artistName)
                }
            )
        }
    }
}

@Composable
private fun SheetRow(
    icon: ImageVector,
    label: String,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    context.startActivity(intent)
}

private fun searchMusic(context: Context, query: String) {
    val searchUrl = "https://www.google.com/search?q=${Uri.encode(query)}"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl))
    context.startActivity(intent)
}

private fun shareNowPlaying(
    context: Context,
    stationName: String,
    trackTitle: String,
    artistName: String
) {
    val hasTrack = trackTitle.isNotBlank() &&
        !trackTitle.equals(stationName, ignoreCase = true)
    val text = if (hasTrack) {
        val parts = mutableListOf<String>()
        if (artistName.isNotBlank()) parts.add(artistName)
        parts.add(trackTitle)
        "Listening to ${parts.joinToString(" - ")} on $stationName"
    } else {
        "Listening to $stationName"
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share Now Playing"))
}

private fun buildSearchQuery(artistName: String, trackTitle: String): String {
    return listOf(artistName, trackTitle)
        .filter { it.isNotBlank() }
        .joinToString(" ")
}
