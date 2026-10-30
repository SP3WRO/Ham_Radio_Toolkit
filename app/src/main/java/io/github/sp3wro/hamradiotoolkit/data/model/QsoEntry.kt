package io.github.sp3wro.hamradiotoolkit.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class QsoEntry(
    val id: String = UUID.randomUUID().toString(),
    val callsign: String,
    val datetimeUtc: String, // format: "yyyy-MM-dd HH:mm"
    val frequency: String,  // np. "14195" (w kHz)
    val band: String,       // np. "20m" (automatycznie wyliczane z częstotliwości)
    val mode: String,       // np. "SSB", "CW", "FT8"
    val rstSent: String,    // np. "59"
    val rstRcvd: String,    // np. "59"
    val comment: String = "",
    val name: String = "",
    val qth: String = "",
    val gridSquare: String = "",
    val dxcc: Int? = null,
    val country: String = "",
    val huntedRef: String = "", // obiekt POTA/WWFF korespondenta, np. "SP-0012" albo "SPFF-0001"
    val qrzUploaded: Boolean = false,
    val eqslUploaded: Boolean = false
)

fun calculateBandFromFreq(freqStr: String): String =
    freqStr.replace(',', '.').toDoubleOrNull()?.let { bandForKhz(it) } ?: "20m"

/** Pasmo amatorskie dla częstotliwości w kHz albo null poza pasmami. */
fun bandForKhz(khz: Double): String? {
    return when {
        khz in 1800.0..2000.0 -> "160m"
        khz in 3500.0..4000.0 -> "80m"
        khz in 5350.0..5450.0 -> "60m"
        khz in 7000.0..7300.0 -> "40m"
        khz in 10100.0..10150.0 -> "30m"
        khz in 14000.0..14350.0 -> "20m"
        khz in 18068.0..18168.0 -> "17m"
        khz in 21000.0..21450.0 -> "15m"
        khz in 24890.0..24990.0 -> "12m"
        khz in 28000.0..29700.0 -> "10m"
        khz in 50000.0..54000.0 -> "6m"
        khz in 70000.0..71000.0 -> "4m"
        khz in 144000.0..148000.0 -> "2m"
        khz in 430000.0..440000.0 -> "70cm"
        khz in 1240000.0..1300000.0 -> "23cm"
        else -> null
    }
}
