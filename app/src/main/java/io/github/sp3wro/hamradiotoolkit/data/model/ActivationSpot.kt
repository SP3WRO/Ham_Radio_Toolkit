package io.github.sp3wro.hamradiotoolkit.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ActivationSpot(
    val id: String,
    val reference: String,
    val name: String,
    val program: String,
    val latitude: Double,
    val longitude: Double,
    @Transient var distanceKm: Double = 0.0
)