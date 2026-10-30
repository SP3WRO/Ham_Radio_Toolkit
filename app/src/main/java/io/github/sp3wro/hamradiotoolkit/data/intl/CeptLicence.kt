package io.github.sp3wro.hamradiotoolkit.data.intl

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.data.geo.CountryInfo

/**
 * Kraj w zaleceniu CEPT T/R 61-01 ("licencja CEPT"): prefiks, który gość stawia przed swoim znakiem.
 * [cept] = członek CEPT (Aneks 2) albo kraj spoza CEPT stosujący zalecenie (Aneks 4).
 */
data class CeptCountry(
    val prefixes: List<String>,
    val cept: Boolean = true,
    val licences: String = "",
    /** Uwaga krajowa – zasób tekstowy. */
    val note: Int? = null,
    /** true: prefiks stawiany jako przyrostek (Peru – "SP3WRO/OA4"). */
    val suffix: Boolean = false
)

data class OperatingAdvice(
    val country: CountryInfo,
    val cept: CeptCountry?,
    /** Gotowy znak do użycia (np. "DL/SP3WRO") albo null, gdy kraj nie stosuje T/R 61-01. */
    val callsign: String?,
    val prefix: String?
)

/**
 * Tabele z zalecenia CEPT T/R 61-01 (ECO docdb, Aneks 2 aktualizacja 02.2024, Aneks 4 – 10.2024).
 * Kraje spoza listy (np. Rosja, Białoruś, Japonia) wymagają osobnego zezwolenia lub umowy dwustronnej.
 */
object CeptLicence {
    const val SOURCE = "CEPT T/R 61-01 (Aneks 2: 02.2024, Aneks 4: 10.2024)"

    private val COUNTRIES: Map<String, CeptCountry> = mapOf(
        // Aneks 2 – kraje CEPT
        "AL" to CeptCountry(listOf("ZA"), licences = "CEPT"),
        "AT" to CeptCountry(listOf("OE"), licences = "1 (dawniej także 2)"),
        "BE" to CeptCountry(listOf("ON"), licences = "A"),
        "BA" to CeptCountry(listOf("E7"), licences = "CEPT 1"),
        "BG" to CeptCountry(listOf("LZ"), licences = "Class 1"),
        "HR" to CeptCountry(listOf("9A"), licences = "CEPT"),
        "CY" to CeptCountry(listOf("5B"), licences = "Radioamateur Authorisation"),
        "CZ" to CeptCountry(listOf("OK"), licences = "A"),
        "DK" to CeptCountry(listOf("OZ"), licences = "A"),
        "FO" to CeptCountry(listOf("OY"), licences = "A"),
        "GL" to CeptCountry(listOf("OX"), licences = "A"),
        "EE" to CeptCountry(listOf("ES"), licences = "A i B",
            note = R.string.cept_note_ee),
        "FI" to CeptCountry(listOf("OH"), licences = "L, P, T, Y"),
        "AX" to CeptCountry(listOf("OH0"), licences = "L, P, T, Y"),
        "FR" to CeptCountry(listOf("F"), licences = "HAREC, klasy 1 i 2"),
        "GP" to CeptCountry(listOf("FG"), licences = "HAREC, klasy 1 i 2"),
        "GF" to CeptCountry(listOf("FY"), licences = "HAREC, klasy 1 i 2"),
        "MQ" to CeptCountry(listOf("FM"), licences = "HAREC, klasy 1 i 2"),
        "BL" to CeptCountry(listOf("FJ"), licences = "HAREC, klasy 1 i 2"),
        "PM" to CeptCountry(listOf("FP"), licences = "HAREC, klasy 1 i 2"),
        "MF" to CeptCountry(listOf("FS"), licences = "HAREC, klasy 1 i 2"),
        "RE" to CeptCountry(listOf("FR"), licences = "HAREC, klasy 1 i 2"),
        "YT" to CeptCountry(listOf("FH"), licences = "HAREC, klasy 1 i 2"),
        "TF" to CeptCountry(listOf("FT"), licences = "HAREC, klasy 1 i 2"),
        "PF" to CeptCountry(listOf("FO"), licences = "HAREC, klasy 1 i 2"),
        "NC" to CeptCountry(listOf("FK"), licences = "HAREC, klasy 1 i 2"),
        "WF" to CeptCountry(listOf("FW"), licences = "HAREC, klasy 1 i 2"),
        "GE" to CeptCountry(listOf("4L"), licences = "A, Extra"),
        "DE" to CeptCountry(listOf("DL"), licences = "1, 2 i A"),
        "GR" to CeptCountry(listOf("SV"), licences = "1"),
        "HU" to CeptCountry(listOf("HA", "HG"), licences = "CEPT; dawniej RB, RC, UB, UC"),
        "IS" to CeptCountry(listOf("TF"), licences = "G"),
        "IE" to CeptCountry(listOf("EI", "EJ"), licences = "1 i 2",
            note = R.string.cept_note_ie),
        "IT" to CeptCountry(listOf("I"), licences = "A"),
        "LV" to CeptCountry(listOf("YL"), licences = "A", note = R.string.cept_note_lv),
        "LI" to CeptCountry(listOf("HB0"), licences = "CEPT"),
        "LT" to CeptCountry(listOf("LY"), licences = "A"),
        "LU" to CeptCountry(listOf("LX"), licences = "CEPT"),
        "MK" to CeptCountry(listOf("Z3"), licences = "A i P"),
        "MD" to CeptCountry(listOf("ER"), licences = "A i B"),
        "MT" to CeptCountry(listOf("9H"), licences = "wszystkie"),
        "MC" to CeptCountry(listOf("3A"), licences = "General"),
        "ME" to CeptCountry(listOf("4O"), licences = "A i N"),
        "NL" to CeptCountry(listOf("PA"), licences = "F"),
        "NO" to CeptCountry(listOf("LA"), licences = "A"),
        "SJ" to CeptCountry(listOf("JW"), licences = "A"),
        "PL" to CeptCountry(listOf("SP"), licences = "1"),
        "PT" to CeptCountry(listOf("CT7"), licences = "1, A i B"),
        "RO" to CeptCountry(listOf("YO"), licences = "I i II"),
        "RS" to CeptCountry(listOf("YU"), licences = "1"),
        "SK" to CeptCountry(listOf("OM"), licences = "E (dawniej A, B, C)"),
        "SI" to CeptCountry(listOf("S5"), licences = "A (dawniej 1, 2, 3)"),
        "ES" to CeptCountry(listOf("EA"), licences = "A"),
        "SE" to CeptCountry(listOf("SM", "SA"), licences = "wszystkie"),
        "CH" to CeptCountry(listOf("HB9"), licences = "1, 2, CEPT"),
        "TR" to CeptCountry(listOf("TA"), licences = "A"),
        "UA" to CeptCountry(listOf("UT"), licences = "A (dawniej 1 i 2)"),
        "GB" to CeptCountry(listOf("M"), licences = "FULL"),
        "IM" to CeptCountry(listOf("MD"), licences = "FULL"),
        "JE" to CeptCountry(listOf("MJ"), licences = "FULL"),
        "GG" to CeptCountry(listOf("MU"), licences = "FULL"),

        // Aneks 4 – kraje spoza CEPT stosujące zalecenie
        "AU" to CeptCountry(listOf("VK"), cept = false, licences = "Advanced",
            note = R.string.cept_note_au),
        "CA" to CeptCountry(listOf("VE3"), cept = false, licences = "Basic i Advanced; gość: uprawnienia Advanced"),
        "KY" to CeptCountry(listOf("ZF"), cept = false, licences = "A"),
        "IL" to CeptCountry(listOf("4X", "4Z"), cept = false, licences = "A, B, C; gość: B (General)"),
        "AW" to CeptCountry(listOf("P4"), cept = false, licences = "A, B, C; gość: A"),
        "CW" to CeptCountry(listOf("PJ2"), cept = false, licences = "A, B, C; gość: A"),
        "BQ" to CeptCountry(listOf("PJ4", "PJ5", "PJ6"), cept = false, licences = "A, B, C; gość: A",
            note = R.string.cept_note_bq),
        "SX" to CeptCountry(listOf("PJ7"), cept = false, licences = "A, B, C; gość: A"),
        "NZ" to CeptCountry(listOf("ZL"), cept = false, licences = "General",
            note = R.string.cept_note_nz),
        "PE" to CeptCountry(listOf("OA"), cept = false, suffix = true,
            note = R.string.cept_note_pe),
        "ZA" to CeptCountry(listOf("ZS"), cept = false, licences = "Restricted i Unrestricted; gość: Unrestricted"),
        "US" to CeptCountry(listOf("W"), cept = false, licences = "Amateur Extra i Advanced; gość: Amateur Extra")
    )

    // Aneks 4 – prefiksy dla stanów i terytoriów USA
    private val US_STATES = mapOf(
        "Alabama" to "W4", "Alaska" to "KL7", "American Samoa" to "KH8", "Arizona" to "W7", "Arkansas" to "W5",
        "California" to "W6", "Colorado" to "W0", "Northern Mariana Islands" to "KH0", "Puerto Rico" to "KP4",
        "Connecticut" to "W1", "Delaware" to "W3", "District of Columbia" to "W3", "Florida" to "W4", "Georgia" to "W4",
        "Guam" to "KH2", "Hawaii" to "KH6", "Idaho" to "W7", "Illinois" to "W9", "Indiana" to "W9", "Iowa" to "W0",
        "Kansas" to "W0", "Kentucky" to "W4", "Louisiana" to "W5", "Maine" to "W1", "Maryland" to "W3",
        "Massachusetts" to "W1", "Michigan" to "W8", "Minnesota" to "W0", "Mississippi" to "W5", "Missouri" to "W0",
        "Montana" to "W7", "Nebraska" to "W0", "Nevada" to "W7", "New Hampshire" to "W1", "New Jersey" to "W2",
        "New Mexico" to "W5", "New York" to "W2", "North Carolina" to "W4", "North Dakota" to "W0", "Ohio" to "W8",
        "Oklahoma" to "W5", "Oregon" to "W7", "Pennsylvania" to "W3", "Rhode Island" to "W1", "South Carolina" to "W4",
        "South Dakota" to "W0", "Tennessee" to "W4", "Texas" to "W5", "Utah" to "W7", "Vermont" to "W1",
        "U.S. Virgin Islands" to "KP2", "Virginia" to "W4", "Washington" to "W7", "West Virginia" to "W8",
        "Wisconsin" to "W9", "Wyoming" to "W7"
    )
    // Terytoria USA z własnym kodem ISO
    private val US_TERRITORIES = mapOf("PR" to "KP4", "VI" to "KP2", "GU" to "KH2", "AS" to "KH8", "MP" to "KH0")

    // Aneks 4 – prowincje i terytoria Kanady
    private val CANADA = mapOf(
        "Nova Scotia" to "VE1", "Quebec" to "VE2", "Québec" to "VE2", "Ontario" to "VE3", "Manitoba" to "VE4",
        "Saskatchewan" to "VE5", "Alberta" to "VE6", "British Columbia" to "VE7", "Northwest Territories" to "VE8",
        "New Brunswick" to "VE9", "Newfoundland and Labrador" to "VO1", "Nunavut" to "VY0", "Yukon" to "VY1",
        "Prince Edward Island" to "VY2"
    )

    // Aneks 2 – części Wielkiej Brytanii
    private val UK = mapOf("England" to "M", "Scotland" to "MM", "Wales" to "MW", "Northern Ireland" to "MI")

    fun find(iso: String): CeptCountry? = COUNTRIES[iso] ?: US_TERRITORIES[iso]?.let { COUNTRIES["US"] }

    /** Prefiks do użycia w danym miejscu (z uwzględnieniem regionów i terytoriów) albo null. */
    fun prefixFor(country: CountryInfo): String? {
        val iso = country.isoCode
        US_TERRITORIES[iso]?.let { return it }
        val entry = COUNTRIES[iso] ?: return null
        val area = country.adminArea
        val pos = country.position
        return when (iso) {
            "US" -> US_STATES[area] ?: "W"
            // Bez prowincji – sam prefiks kraju; właściwą cyfrę podaje Aneks 4 (VE1–VE9, VO, VY)
            "CA" -> CANADA[area] ?: CANADA.entries.firstOrNull { area.contains(it.key, ignoreCase = true) }?.value ?: "VE"
            "GB" -> UK[area] ?: "M"
            "PT" -> when {
                pos != null && pos.lon < -24 -> "CT8"                       // Azory
                pos != null && pos.lat < 34 && pos.lon < -15 -> "CT9"       // Madera
                else -> "CT7"
            }
            // Korsyka ma własny prefiks w Aneksie 2
            "FR" -> if (pos != null && pos.lat in 41.3..43.1 && pos.lon in 8.5..9.6) "TK" else "F"
            else -> entry.prefixes.first()
        }
    }

    /** Porada dla [ownCall] w kraju [country]. Dla Polski (lub polskiego znaku w Polsce) bez prefiksu. */
    fun advice(country: CountryInfo, ownCall: String): OperatingAdvice {
        val entry = find(country.isoCode)
        val prefix = prefixFor(country)
        val call = ownCall.trim().uppercase().takeIf { it.isNotEmpty() && it != "NOCALL" }
        val full = when {
            prefix == null || call == null -> null
            country.isPoland -> call
            entry?.suffix == true -> "$call/${prefix}"
            else -> "$prefix/$call"
        }
        return OperatingAdvice(country, entry, full, prefix)
    }

    /** Ręczny wybór kraju (lista do ekranu "Za granicą"), posortowana po polskiej nazwie. */
    fun allCountries(): List<CountryInfo> =
        (COUNTRIES.keys + US_TERRITORIES.keys).map { CountryInfo(it) }.sortedBy { it.namePl }
}
