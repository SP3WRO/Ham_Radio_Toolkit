package io.github.sp3wro.hamradiotoolkit.data.beacons

data class Beacon(val call: String, val entity: String, val location: String, val gridSquare: String)

data class BeaconSlot(val bandKhz: Int, val beacon: Beacon, val next: Beacon, val secondsLeft: Int)

/**
 * NCDXF/IARU International Beacon Project (https://www.ncdxf.org/beacon/):
 * 18 beaconów nadaje po kolei po 10 s na 5 pasmach, cykl 3 minuty liczony od pełnej godziny UTC.
 * Beacon nr i (od 0) nadaje na paśmie nr b w szczelinie (i + b) mod 18.
 */
object NcdxfBeacons {
    const val SLOT_SECONDS = 10
    const val CYCLE_SECONDS = 180

    val BANDS_KHZ = listOf(14100, 18110, 21150, 24930, 28200)

    // Kolejność i lokatory według ncdxf.org/beacon/beaconlocations.html
    val BEACONS = listOf(
        Beacon("4U1UN", "ONZ", "Nowy Jork", "FN30as"),
        Beacon("VE8AT", "Kanada", "Inuvik, NT", "CP38gh"),
        Beacon("W6WX", "USA", "Mt. Umunhum", "CM97bd"),
        Beacon("KH6RS", "Hawaje", "Maui", "BL10ts"),
        Beacon("ZL6B", "Nowa Zelandia", "Masterton", "RE78tw"),
        Beacon("VK6RBP", "Australia", "Rolystone", "OF87av"),
        Beacon("JA2IGY", "Japonia", "Mt. Asama", "PM84jk"),
        Beacon("RR9O", "Rosja", "Nowosybirsk", "NO14kx"),
        Beacon("VR2B", "Hongkong", "Hongkong", "OL72bg"),
        Beacon("4S7B", "Sri Lanka", "Kolombo", "MJ96wv"),
        Beacon("ZS6DN", "RPA", "Pretoria", "KG33xi"),
        Beacon("5Z4B", "Kenia", "Kikuyu", "KI88hr"),
        Beacon("4X6TU", "Izrael", "Tel Awiw", "KM72jb"),
        Beacon("OH2B", "Finlandia", "Lohja", "KP20eh"),
        Beacon("CS3B", "Madera", "São Jorge", "IM12jt"),
        Beacon("LU4AA", "Argentyna", "Buenos Aires", "GF05tj"),
        Beacon("OA4B", "Peru", "Lima", "FH17mw"),
        Beacon("YV5B", "Wenezuela", "Caracas", "FK60nd")
    )

    /** Który beacon nadaje teraz na każdym paśmie. [utcSecondOfDay] – sekunda doby UTC. */
    fun nowTransmitting(utcSecondOfDay: Long): List<BeaconSlot> {
        val inCycle = Math.floorMod(utcSecondOfDay, CYCLE_SECONDS.toLong()).toInt()
        val slot = inCycle / SLOT_SECONDS
        val secondsLeft = SLOT_SECONDS - inCycle % SLOT_SECONDS
        return BANDS_KHZ.mapIndexed { band, khz ->
            val index = Math.floorMod(slot - band, BEACONS.size)
            BeaconSlot(khz, BEACONS[index], BEACONS[(index + 1) % BEACONS.size], secondsLeft)
        }
    }

    /** Za ile sekund [beacon] zacznie nadawać na paśmie [bandIndex] (0, jeśli nadaje teraz). */
    fun secondsUntil(beacon: Beacon, bandIndex: Int, utcSecondOfDay: Long): Int {
        val beaconIndex = BEACONS.indexOf(beacon)
        val start = Math.floorMod(beaconIndex + bandIndex, BEACONS.size) * SLOT_SECONDS
        val inCycle = Math.floorMod(utcSecondOfDay, CYCLE_SECONDS.toLong()).toInt()
        if (inCycle in start until start + SLOT_SECONDS) return 0
        return Math.floorMod(start - inCycle, CYCLE_SECONDS)
    }
}
