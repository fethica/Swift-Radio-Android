package com.fethica.swiftradio.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ITunesResponse(
    val results: List<ITunesResult> = emptyList()
)

@Serializable
private data class ITunesResult(
    val artworkUrl100: String = ""
)

class ArtworkService(private val artworkSize: Int = 600) {

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(
                Json { ignoreUnknownKeys = true },
                contentType = ContentType.Any
            )
        }
    }

    suspend fun fetchArtworkUrl(rawMetadata: String): String? {
        if (rawMetadata.isBlank()) return null

        return try {
            val response: ITunesResponse = client.get("https://itunes.apple.com/search") {
                parameter("term", rawMetadata)
                parameter("entity", "song")
            }.body()

            response.results.firstOrNull()?.artworkUrl100
                ?.takeIf { it.isNotBlank() }
                ?.let { url ->
                    if (artworkSize != 100 && artworkSize > 0) {
                        url.replace("100x100", "${artworkSize}x${artworkSize}")
                    } else {
                        url
                    }
                }
        } catch (e: Exception) {
            android.util.Log.e("SwiftRadio", "Artwork fetch failed", e)
            null
        }
    }
}
