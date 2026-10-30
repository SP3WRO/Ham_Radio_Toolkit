package io.github.sp3wro.hamradiotoolkit.data.model

data class AprsPacket(
    val callsign: String,
    val latitude: Double,
    val longitude: Double,
    val comment: String,
    val table: Char,
    val symbol: Char,
    val timestampMs: Long,
    var distanceKm: Double = 0.0,
    
    // Symbol Overlay (np. 'D' dla digipeatera w symbolu z nakładką)
    var overlay: Char? = null,
    
    // Dane o Ruchu (Mobilne)
    var speedKmh: Double? = null,
    var courseDegrees: Int? = null,
    var altitudeM: Double? = null,

    // Dane o stacji (PHG)
    var powerW: Int? = null,
    var antennaHeightM: Double? = null,
    var antennaGainDb: Int? = null,
    var directivityDeg: Int? = null,
    
    // Dane Pogodowe WX
    var tempCelsius: Double? = null,
    var windSpeedKmh: Double? = null,
    var windGustKmh: Double? = null,
    var windDirDeg: Int? = null,
    var humidityPercent: Int? = null,
    var pressureHpa: Double? = null,
    var rain1hMm: Double? = null,
    var rain24hMm: Double? = null,
    var rainMidnightMm: Double? = null
)
