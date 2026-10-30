package io.github.sp3wro.hamradiotoolkit.data.model

import io.github.sp3wro.hamradiotoolkit.AppStrings
import io.github.sp3wro.hamradiotoolkit.R
import java.util.Locale

/**
 * Tłumaczenie tekstów zapisanych po polsku z powtarzających się zwrotów: w innych językach podmieniamy zwroty
 * na tłumaczenia z zasobów; częstotliwości, nazwy emisji i skróty zostają bez zmian.
 */
open class PhraseLocalizer(phrases: List<Pair<String, Int>>) {
    // Dłuższe zwroty przed krótszymi, które w nich występują
    private val patterns = phrases.sortedByDescending { it.first.length }.map { (phrase, id) ->
        Regex("(?<!\\p{L})(" + Regex.escape(phrase) + "|" + Regex.escape(phrase.capitalizeFirst()) + ")(?!\\p{L})") to id
    }

    fun localize(text: String): String {
        if (Locale.getDefault().language == "pl" || text.isEmpty()) return text
        var result = text
        for ((regex, id) in patterns) {
            if (!regex.containsMatchIn(result)) continue
            val translated = AppStrings.get(id)
            result = regex.replace(result) { m ->
                if (m.value.first().isUpperCase()) translated.capitalizeFirst() else translated
            }
        }
        return result
    }

    private fun String.capitalizeFirst(): String = replaceFirstChar { it.uppercase() }
}

/** Opisy segmentów bandplanów i częstotliwości regionalnych. */
object BandplanText : PhraseLocalizer(
    listOf(
        "globalna częstotliwość kryzysowa" to R.string.bp_ph_global_emergency,
        "łączności międzykontynentalne" to R.string.bp_ph_intercontinental,
        "łączność kryzysowa" to R.string.bp_ph_emergency_traffic,
        "kryzysowa" to R.string.bp_ph_emergency,
        "zautomatyzowane stacje" to R.string.bp_ph_automated_stations,
        "automatyczne stacje" to R.string.bp_ph_automatic_stations2,
        "stacje automatyczne" to R.string.bp_ph_automatic_stations,
        "okno DX" to R.string.bp_ph_dx_window,
        "tylko kierunek Ziemia–kosmos" to R.string.bp_ph_earth_space,
        "tylko CW" to R.string.bp_ph_cw_only,
        "wąskie emisje" to R.string.bp_ph_narrow_modes,
        "wąskopasmowe" to R.string.bp_ph_narrowband,
        "emisje cyfrowe" to R.string.bp_ph_digital_modes,
        "szerokopasmowe" to R.string.bp_ph_wideband,
        "wszystkie emisje" to R.string.bp_ph_all_modes,
        "wszystkie" to R.string.bp_ph_all,
        "fonia na USB" to R.string.bp_ph_phone_usb,
        "fonia" to R.string.bp_ph_phone,
        "fonię" to R.string.bp_ph_phone_acc,
        "obraz" to R.string.bp_ph_image,
        "zawody" to R.string.bp_ph_contests,
        "słabe sygnały" to R.string.bp_ph_weak_signals,
        "międzynarodowe wywołanie" to R.string.bp_ph_intl_calling,
        "wywołanie" to R.string.bp_ph_calling,
        "beacony międzynarodowe" to R.string.bp_ph_intl_beacons,
        "bez beaconów CW" to R.string.bp_ph_no_cw_beacons,
        "beacony cyfrowe" to R.string.bp_ph_digital_beacons,
        "beacony" to R.string.bp_ph_beacons,
        "regionalne i lokalne" to R.string.bp_ph_regional_local,
        "zdalne sterowanie" to R.string.bp_ph_remote_control,
        "uplink satelitów" to R.string.bp_ph_sat_uplink,
        "APRS satelitarny" to R.string.bp_ph_sat_aprs,
        "satelity" to R.string.bp_ph_satellites,
        "wyłącznie" to R.string.bp_ph_exclusive,
        "eksperymenty" to R.string.bp_ph_experiments,
        "sub-band amatorski" to R.string.bp_ph_amateur_subband,
        "sub-band komunikacji lokalnej" to R.string.bp_ph_local_subband,
        "wejścia przemienników" to R.string.bp_ph_rep_inputs,
        "wyjścia przemienników" to R.string.bp_ph_rep_outputs,
        "wejścia i wyjścia" to R.string.bp_ph_inputs_outputs,
        "wyjście" to R.string.bp_ph_output,
        "wejście" to R.string.bp_ph_input,
        "przemienniki" to R.string.bp_ph_repeaters,
        "opcja lokalna" to R.string.bp_ph_local_option,
        "zwykle" to R.string.bp_ph_usually,
        "wg planów krajowych" to R.string.bp_ph_national_plans,
        "wg krajowych przydziałów" to R.string.bp_ph_national_alloc,
        "łącza" to R.string.bp_ph_links,
        "bramki internetowe" to R.string.bp_ph_gateways,
        "telegrafia" to R.string.bp_ph_telegraphy,
        "kanał" to R.string.bp_ph_channel,
        "cyfrowe" to R.string.bp_ph_digital,
        "brak SSB!" to R.string.bp_ph_no_ssb,
        "niektóre kraje dopuszczają" to R.string.bp_ph_some_countries,
        "zalecana" to R.string.bp_ph_recommended,
        "Europa" to R.string.bp_ph_europe,
        "Karaiby: chroniona też" to R.string.bp_ph_caribbean,
        "część krajów używa innej – sprawdź krajowy bandplan" to R.string.bp_ph_other_freq,
        "na UKF" to R.string.bp_ph_on_vhf,
        "także" to R.string.bp_ph_also,
        "i" to R.string.bp_ph_and,
        "do" to R.string.bp_ph_up_to,
        "od" to R.string.bp_ph_from,
        "w" to R.string.bp_ph_in
    )
)

/** Opisy wymiany i pasm we wbudowanym kalendarzu zawodów. */
object ContestText : PhraseLocalizer(
    listOf(
        "stacje europejskie pracują z pozaeuropejskimi" to R.string.cx_eu_non_eu,
        "QTC między wszystkimi stacjami" to R.string.cx_qtc_all,
        "łączności tylko z" to R.string.cx_contacts_only_with,
        "numer kolejny" to R.string.cx_serial,
        "moc nadajnika" to R.string.cx_tx_power,
        "wiek operatora" to R.string.cx_operator_age,
        "Azją" to R.string.cx_asia,
        "numer członkowski" to R.string.cx_member_number,
        "lub kraj" to R.string.cx_or_country,
        "imię" to R.string.cx_name,
        "strefa CQ" to R.string.cx_cq_zone,
        "strefa ITU" to R.string.cx_itu_zone,
        "stacje HQ" to R.string.cx_hq_stations,
        "skrót stowarzyszenia" to R.string.cx_society,
        "np." to R.string.cx_eg,
        "stacje SP" to R.string.cx_sp_stations,
        "litera województwa" to R.string.cx_province_letter,
        "kod komitatu" to R.string.cx_county_code,
        "kod obwodu" to R.string.cx_oblast_code,
        "kod powiatu" to R.string.cx_district_code,
        "stan/prowincja" to R.string.cx_state_province,
        "lokator" to R.string.cx_locator,
        "znaki" to R.string.cx_chars,
        "znaków" to R.string.cx_chars_gen,
        "i wyżej" to R.string.cx_and_up,
        "także" to R.string.cx_also
    )
)
