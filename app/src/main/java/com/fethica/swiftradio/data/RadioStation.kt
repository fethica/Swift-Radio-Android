package com.fethica.swiftradio.data

import kotlinx.serialization.Serializable

@Serializable
data class RadioStation(
    val name: String,
    val streamURL: String,
    val imageURL: String,
    val desc: String,
    val longDesc: String = "",
    val website: String = ""
)

@Serializable
data class StationsResponse(
    val station: List<RadioStation>
)
