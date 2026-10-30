package io.github.sp3wro.hamradiotoolkit.data.model

data class SolarData(
    val updated: String = "",
    val solarFlux: String = "",
    val aIndex: String = "",
    val kIndex: String = "",
    val sunspots: String = "",
    val hfConditions: List<BandCondition> = emptyList(),
    val vhfConditions: List<VhfCondition> = emptyList()
)

data class BandCondition(
    val name: String,
    val time: String,
    val condition: String
)

data class VhfCondition(
    val name: String,
    val location: String,
    val condition: String
)