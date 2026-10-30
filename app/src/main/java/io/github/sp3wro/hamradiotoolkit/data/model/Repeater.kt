package io.github.sp3wro.hamradiotoolkit.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@Serializable
data class RepeaterResponse(
    val callsign: String,
    val modes: List<String> = emptyList(),
    @SerialName("tx_frequency") val txFrequency: Double? = 0.0,
    @SerialName("rx_frequency") val rxFrequency: Double? = 0.0,
    @SerialName("tx_ctcss") val txCtcssRaw: JsonElement? = null,
    @SerialName("rx_ctcss") val rxCtcssRaw: JsonElement? = null,
    val status: String = "",
    val coordinates: List<Double> = emptyList(),
    val qth: String = "",
    val locator: String = ""
) {
    fun getRxCtcssString(): String {
        val raw = rxCtcssRaw ?: return "-"
        if (raw is JsonPrimitive) {
            if (raw.isString) return raw.content
            if (raw.booleanOrNull == false) return "-"
            return raw.content
        }
        return "-"
    }
}

data class Repeater(
    val callsign: String,
    val qth: String,
    val modes: List<String>,
    val userRx: Double, // repeater's tx
    val userTx: Double, // repeater's rx
    val requiredCtcss: String, // repeater's rx ctcss
    val status: String,
    val latitude: Double,
    val longitude: Double,
    var distanceKm: Double = 0.0,
    /** Źródło danych (nazwa wyświetlana), np. "HearHam" albo "HearHam + repeatermap.de" po scaleniu. */
    val source: String = "",
    /** Uwagi ze źródła: sieć (Allstar, IRLP), opis, link. */
    val note: String = "",
    /** Color code DMR, jeśli źródło go podaje (np. "1"). */
    val dmrColorCode: String = ""
) {
    /** Klucz wiersza listy – musi być unikalny, inaczej LazyColumn zamyka aplikację. */
    val listKey: String get() = "${callsign}_${userRx}_${userTx}_${latitude}_${longitude}"
}

/**
 * Jeden przemiennik z wszystkimi jego częstotliwościami (np. 2 m i 70 cm pod tym samym znakiem
 * i w tym samym miejscu) – źródła podają je jako osobne wpisy, a na liście to jedna karta.
 */
data class RepeaterGroup(val channels: List<Repeater>) {
    private val first get() = channels.first()
    val callsign: String get() = first.callsign
    val qth: String get() = channels.map { it.qth }.firstOrNull { it.isNotBlank() } ?: ""
    val distanceKm: Double get() = channels.minOf { it.distanceKm }
    val isWorking: Boolean get() = channels.any { it.status == "working" }
    val listKey: String get() = first.listKey

    companion object {
        /** Grupuje po znaku i położeniu (~1 km); kolejność grup jak kolejność pierwszych wpisów. */
        fun of(repeaters: List<Repeater>): List<RepeaterGroup> =
            repeaters.groupBy { "${it.callsign.uppercase()}@${Math.round(it.latitude * 100)}/${Math.round(it.longitude * 100)}" }
                .values.map { channels -> RepeaterGroup(channels.sortedBy { it.userRx }) }
    }
}