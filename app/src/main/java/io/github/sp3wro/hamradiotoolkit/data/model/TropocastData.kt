package io.github.sp3wro.hamradiotoolkit.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TropocastMetadata(
    val run: String = "",
    @SerialName("generated_at") val generatedAt: String = "",
    val frames: List<TropocastFrame> = emptyList()
)

@Serializable
data class TropocastFrame(
    val hour: Int,
    @SerialName("valid_at") val validAt: String,
    val run: String? = null,
    @SerialName("forecast_hour") val forecastHour: Int? = null
)