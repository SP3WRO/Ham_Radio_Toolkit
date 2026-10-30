package io.github.sp3wro.hamradiotoolkit.data.awards

import io.github.sp3wro.hamradiotoolkit.data.contests.CivilDate

/**
 * Strona akcji radiodyplom.pl/ses/<id>: czas trwania, zasady powtórzeń, stacje z punktacją, dyplomy
 * i ranking uczestników. Strona nie ma API – czytamy HTML, więc każda część jest opcjonalna:
 * brak sekcji (zmiana wyglądu serwisu) daje pustą listę, a nie błąd.
 */
object RadiodyplomEventPage {
    private const val MAX_DESCRIPTION = 6000

    private val OG_DESCRIPTION = Regex("""<meta property="og:description" content="([^"]*)"""")
    private val ORGANIZERS = Regex("""(?s)Organizatorzy:</span>\s*<span class="ses-detail-value">(.*?)</span>""")
    private val DESCRIPTION = Regex("""(?s)<div class="ses-description">(.*?)</div>""")
    private val DURATION = Regex(
        """(?s)Czas trwania akcji</h3>\s*<div class="ses-rule-dates">\s*<div>\s*Od\s*<time>([^<]+)</time>.*?Do\s*<time>([^<]+)</time>"""
    )
    private val UTC_TIME = Regex("""(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})""")
    private val REPEAT_RULE = Regex("""(?s)Kiedy kolejna łączność daje punkty\?</h3>\s*<p>(.*?)</p>""")
    private val MODE_GROUP = Regex("""(?s)<div class="ses-mode-group">(.*?)</section>""")
    private val MODE_TAG = Regex("""<span class="mode-tag">([^<]+)</span>""")
    private val BAND_TAG = Regex("""<span class="band-tag">([^<]+)</span>""")
    private val STATION_CALL = Regex("""ses_callsign=([A-Z0-9/]+)"""")
    private val STATION_POINTS = Regex("""<strong class="ses-station-points">(\d+)</strong>""")
    private val STATION_TYPE = Regex("""(?s)>badge</span>\s*([^<]+?)\s*</span>""")
    private val AWARD_TITLE = Regex("""(?s)<h3 class="award-title">.*?</span>\s*(.*?)\s*</h3>""")
    private val AWARD_MIN = Regex("""(?s)Należy zdobyć minimum:</p>.*?<strong[^>]*>(\d+)</strong>\s*([^\s<]+)""")
    private val ALLOWED_COUNTRY = Regex("""<span class="tag is-success is-light mr-1 mb-1">([^<]+)</span>""")
    private val ALLOWED_CONTINENT = Regex("""<span class="tag is-info is-light mr-1 mb-1">([^<]+)</span>""")
    private val EXCLUDED = Regex("""<span class="tag is-danger is-light mr-1 mb-1">([^<]+)</span>""")
    private val RANKING_ROW = Regex("""<tr class="ranking-row""")
    private val RANKING_UPDATED = Regex("""(?s)Ostatnia aktualizacja rankingu \(cache\):\s*</span>\s*<span[^>]*>([^<]+)</span>""")

    fun parse(html: String, eventId: String, callsign: String, nowMs: Long = System.currentTimeMillis()): AwardDetails {
        val duration = DURATION.find(html)
        val repeatRule = REPEAT_RULE.find(html)?.groupValues?.get(1)?.let(::htmlToText).orEmpty()
        val call = callsign.trim().uppercase()
        return AwardDetails(
            eventId = eventId,
            name = OG_DESCRIPTION.find(html)?.groupValues?.get(1)?.let(::decodeEntities)?.trim().orEmpty(),
            startMs = duration?.groupValues?.get(1)?.let(::utc),
            endMs = duration?.groupValues?.get(2)?.let(::utc)?.let { it + 60_000L },
            organizers = ORGANIZERS.find(html)?.groupValues?.get(1)?.let(::htmlToText).orEmpty(),
            description = DESCRIPTION.find(html)?.groupValues?.get(1)?.let(::htmlToText)?.take(MAX_DESCRIPTION).orEmpty(),
            repeatRule = repeatRule,
            repeatLimit = repeatLimit(repeatRule),
            digiGroup = MODE_GROUP.find(html)?.groupValues?.get(1)?.let { group ->
                MODE_TAG.findAll(group).map { it.groupValues[1].trim().uppercase() }.toList()
            }.orEmpty(),
            hasTimeMultipliers = html.contains("Harmonogram mnożników czasowych"),
            stations = stations(html),
            awards = awards(html),
            participants = RANKING_ROW.findAll(html).count(),
            rankingUpdated = RANKING_UPDATED.find(html)?.groupValues?.get(1)?.trim().orEmpty(),
            rankingCallsign = call,
            myRanking = if (call.isEmpty()) null else ranking(html, call),
            fetchedAtMs = nowMs
        )
    }

    /** Karty stacji: znak, punkty i typ stacji. Ta sama stacja może mieć kilka kart – zostaje pierwsza. */
    internal fun stations(html: String): List<AwardStation> {
        val start = html.indexOf("<!-- Lista stacji -->").takeIf { it >= 0 } ?: return emptyList()
        val end = html.indexOf("class=\"ses-public-rules", start).takeIf { it > 0 } ?: html.length
        return html.substring(start, end).split("<!-- Górny róg").drop(1).mapNotNull { card ->
            val call = STATION_CALL.find(card)?.groupValues?.get(1) ?: return@mapNotNull null
            val points = STATION_POINTS.find(card)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            AwardStation(call, points, STATION_TYPE.find(card)?.groupValues?.get(1)?.trim().orEmpty())
        }.distinctBy { it.callsign }
    }

    internal fun awards(html: String): List<AwardRule> {
        val parts = html.split("<div class=\"award-card\"").drop(1)
        return parts.mapNotNull { raw ->
            // Ostatnia karta ciągnie się do końca strony – ucinamy ją przed rankingiem
            val card = raw.substringBefore("Ranking uczestników całej akcji").take(30_000)
            val title = AWARD_TITLE.find(card)?.groupValues?.get(1)?.let(::htmlToText)?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val min = AWARD_MIN.find(card)
            val allowedSection = card.substringAfter("Dozwolone regiony geograficzne", "").substringBefore("Niedozwolone regiony")
            AwardRule(
                title = title,
                minPoints = min?.groupValues?.get(1)?.toIntOrNull(),
                unit = min?.groupValues?.get(2)?.trim().orEmpty().ifEmpty { "punktów" },
                bands = BAND_TAG.findAll(card).map { it.groupValues[1].trim() }.distinct().toList(),
                modes = MODE_TAG.findAll(card).map { it.groupValues[1].trim().uppercase() }.distinct().toList(),
                allowedRegions = (ALLOWED_COUNTRY.findAll(allowedSection) + ALLOWED_CONTINENT.findAll(allowedSection))
                    .map { decodeEntities(it.groupValues[1].trim()) }.toList(),
                excludedRegions = EXCLUDED.findAll(card).map { decodeEntities(it.groupValues[1].trim()) }.toList()
            )
        }
    }

    /** Wiersz rankingu dla znaku: miejsce, punkty, QSO. */
    internal fun ranking(html: String, callsign: String): RankingEntry? {
        val row = Regex(
            """(?s)<td class="has-text-centered">(\d+)\.</td>\s*<td[^>]*>\s*<a href="[^"]*sesParticipant\.php\?ses_id=\d+&(?:amp;)?callsign=""" +
                Regex.escape(callsign) + """">[^<]*</a>\s*</td>\s*<td[^>]*>(\d+)</td>\s*<td[^>]*>(\d+)</td>"""
        ).find(html) ?: return null
        val g = row.groupValues
        return RankingEntry(g[1].toInt(), g[2].toInt(), g[3].toInt())
    }

    internal fun repeatLimit(rule: String): RepeatLimit {
        val r = rule.lowercase()
        return when {
            "pasmo + emisja" in r || "pasmo i emisja" in r -> if ("dziennie" in r) RepeatLimit.BAND_MODE_DAILY else RepeatLimit.BAND_MODE
            "pasm" in r -> RepeatLimit.BAND
            r.isNotBlank() && ("jedn" in r || "raz" in r) -> RepeatLimit.STATION
            else -> RepeatLimit.BAND_MODE
        }
    }

    private fun utc(s: String): Long? {
        val g = UTC_TIME.find(s)?.groupValues ?: return null
        val y = g[1].toInt()
        val mo = g[2].toInt()
        val d = g[3].toInt()
        if (mo !in 1..12 || d !in 1..CivilDate.lengthOfMonth(y, mo)) return null
        return CivilDate(y, mo, d).epochMillis + (g[4].toInt() * 60 + g[5].toInt()) * 60_000L
    }

    internal fun htmlToText(html: String): String =
        decodeEntities(
            // Jak w przeglądarce: znaki końca wiersza w źródle to zwykłe odstępy, wiersze dzielą <br> i <p>
            html.replace(Regex("""\s+"""), " ")
                .replace(Regex("""(?i)<br\s*/?>"""), "\n")
                .replace(Regex("""(?i)</p>"""), "\n")
                .replace(Regex("""<[^>]+>"""), "")
        )
            .replace(' ', ' ')
            .replace("\uFEFF", "")
            .replace(Regex("""[ \t]+"""), " ")
            .replace(Regex(""" *\n *"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()

    private fun decodeEntities(s: String): String = s
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&bdquo;", "„").replace("&rdquo;", "”")
        .replace("&ndash;", "–").replace("&mdash;", "—")
        .replace("&amp;", "&")
}
