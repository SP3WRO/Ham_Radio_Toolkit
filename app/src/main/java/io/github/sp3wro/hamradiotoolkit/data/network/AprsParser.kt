package io.github.sp3wro.hamradiotoolkit.data.network

import io.github.sp3wro.hamradiotoolkit.data.model.AprsPacket

object AprsParser {

    private const val KNOTS_TO_KMH = 1.852
    private const val MPH_TO_KMH = 1.60934
    private const val HUNDREDTHS_INCH_TO_MM = 0.254
    private const val FEET_TO_M = 0.3048

    // Pola raportu pogodowego APRS i ich długość (APRS 1.0.1, rozdz. 12)
    private val WEATHER_FIELD_LENGTHS = mapOf(
        'c' to 3, 's' to 3, 'g' to 3, 't' to 3,
        'r' to 3, 'p' to 3, 'P' to 3, 'h' to 2, 'b' to 5,
        'L' to 3, 'l' to 3, '#' to 3
    )

    /** Kod symbolu APRS: tabela (/, \ albo nakładka: cyfra lub wielka litera) i znak symbolu. */
    fun isValidSymbolCode(code: String): Boolean =
        code.length == 2 &&
            (code[0] == '/' || code[0] == '\\' || code[0] in '0'..'9' || code[0] in 'A'..'Z') &&
            code[1].code in 33..126

    // Passcode dla APRS-IS (hash znaku bez SSID)
    fun passcode(call: String): Int {
        val cleanCall = call.uppercase().substringBefore('-')
        var hash = 0x73e2
        var i = 0
        while (i < cleanCall.length) {
            hash = hash xor (cleanCall[i].code shl 8)
            if (i + 1 < cleanCall.length) {
                hash = hash xor cleanCall[i + 1].code
            }
            i += 2
        }
        return hash and 0x7fff
    }

    // Parser pakietów pozycji: nieskompresowanych, skompresowanych (rozdz. 9) i Mic-E (rozdz. 10)
    fun parse(line: String, timestampMs: Long = System.currentTimeMillis()): AprsPacket? {
        val callsign = line.substringBefore('>', "")
        if (callsign.isEmpty()) return null

        val info = line.substringAfter(':', "")
        if (info.isEmpty()) return null

        if (info[0] == '`' || info[0] == '\'') {
            val destination = line.substringAfter('>').substringBefore(':').substringBefore(',').substringBefore('-')
            return parseMicE(callsign, destination, info, timestampMs)
        }

        val posStart = when (info[0]) {
            '=', '!' -> 1
            '@', '/' -> 8 // pozycja poprzedzona znacznikiem czasu
            else -> return null
        }
        if (info.length <= posStart) return null

        val packet: AprsPacket
        var comment: String
        if (info[posStart].isDigit()) {
            if (info.length < posStart + 19) return null
            val latStr = info.substring(posStart, posStart + 8)
            val lonStr = info.substring(posStart + 9, posStart + 18)
            val latitude = parseCoordinate(latStr, degreeDigits = 2, negativeDir = 'S') ?: return null
            val longitude = parseCoordinate(lonStr, degreeDigits = 3, negativeDir = 'W') ?: return null
            packet = newPacket(callsign, latitude, longitude, info[posStart + 8], info[posStart + 18], timestampMs) ?: return null
            comment = info.substring(posStart + 19)

            // Rozszerzenie "ddd/sss": dla stacji WX kierunek i prędkość wiatru (mph),
            // dla pozostałych kurs i prędkość (węzły)
            val dirSpeed = Regex("^([0-9.]{3})/([0-9.]{3})").find(comment)
            if (dirSpeed != null) {
                val direction = dirSpeed.groupValues[1].toIntOrNull()
                val speed = dirSpeed.groupValues[2].toIntOrNull()
                if (packet.symbol == '_') {
                    packet.windDirDeg = direction
                    packet.windSpeedKmh = speed?.let { it * MPH_TO_KMH }
                    comment = comment.substring(7)
                } else if (direction != null && speed != null) {
                    packet.courseDegrees = direction
                    packet.speedKmh = speed * KNOTS_TO_KMH
                    comment = comment.substring(7)
                }
            }
        } else {
            packet = parseCompressed(callsign, info, posStart, timestampMs) ?: return null
            comment = info.substring(posStart + 13)
        }
        return finishPacket(packet, comment)
    }

    private fun newPacket(callsign: String, lat: Double, lon: Double, tableChar: Char, symbol: Char, timestampMs: Long): AprsPacket? {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        // Nakładka (APRS 1.0.1, rozdz. 20): cyfra lub litera w miejscu tabeli oznacza symbol
        // z tabeli alternatywnej '\' z tym znakiem nadrukowanym na ikonie (np. "1#" = digi z cyfrą 1)
        val overlay = tableChar.takeIf { it in 'A'..'Z' || it in '0'..'9' }
        val table = if (tableChar == '/') '/' else '\\'
        return AprsPacket(
            callsign = callsign,
            latitude = lat,
            longitude = lon,
            comment = "",
            table = table,
            symbol = symbol,
            timestampMs = timestampMs,
            overlay = overlay
        )
    }

    // Pozycja skompresowana: /YYYYXXXX$csT – współrzędne w base91, w tabeli a-j to nakładki 0-9
    private fun parseCompressed(callsign: String, info: String, posStart: Int, timestampMs: Long): AprsPacket? {
        if (info.length < posStart + 13) return null
        val data = info.substring(posStart, posStart + 13)
        if (data.substring(1, 9).any { it.code !in 33..123 }) return null
        var tableChar = data[0]
        if (tableChar !in "/\\" && tableChar !in 'A'..'Z' && tableChar !in 'a'..'j') return null
        if (tableChar in 'a'..'j') tableChar = '0' + (tableChar - 'a')

        val lat = 90.0 - base91(data.substring(1, 5)) / 380926.0
        val lon = -180.0 + base91(data.substring(5, 9)) / 190463.0
        val packet = newPacket(callsign, lat, lon, tableChar, data[9], timestampMs) ?: return null

        val c = data[10]
        val s = data[11]
        val type = data[12].code - 33
        if (c != ' ' && s.code >= 33) {
            when {
                // Bity źródła NMEA = GGA: pole cs to wysokość w stopach
                type >= 0 && type and 0x18 == 0x10 ->
                    packet.altitudeM = Math.pow(1.002, ((c.code - 33) * 91 + (s.code - 33)).toDouble()) * FEET_TO_M
                c.code - 33 in 0..89 -> {
                    val course = (c.code - 33) * 4
                    val speedKnots = Math.pow(1.08, (s.code - 33).toDouble()) - 1
                    if (packet.symbol == '_') {
                        packet.windDirDeg = course
                        packet.windSpeedKmh = speedKnots * KNOTS_TO_KMH
                    } else {
                        packet.courseDegrees = course.takeIf { it > 0 }
                        packet.speedKmh = speedKnots * KNOTS_TO_KMH
                    }
                }
            }
        }
        return packet
    }

    // Mic-E: szerokość geograficzna i flagi zakodowane w adresie docelowym, reszta w polu informacyjnym
    private fun parseMicE(callsign: String, destination: String, info: String, timestampMs: Long): AprsPacket? {
        if (destination.length < 6 || info.length < 9) return null
        val digits = CharArray(6)
        for (i in 0 until 6) {
            digits[i] = when (val ch = destination[i]) {
                in '0'..'9' -> ch
                in 'A'..'J' -> '0' + (ch - 'A')
                in 'P'..'Y' -> '0' + (ch - 'P')
                'K', 'L', 'Z' -> '0' // niejednoznaczność pozycji – cyfra pominięta
                else -> return null
            }
        }
        fun flag(i: Int) = destination[i] in 'P'..'Z'
        val north = flag(3)
        val lonOffset = flag(4)
        val west = flag(5)

        val latDeg = String(digits, 0, 2).toInt()
        val latMin = String(digits, 2, 2).toInt() + String(digits, 4, 2).toInt() / 100.0
        if (latDeg > 90 || latMin >= 60) return null
        val lat = (latDeg + latMin / 60.0).let { if (north) it else -it }

        var lonDeg = info[1].code - 28
        if (lonOffset) lonDeg += 100
        if (lonDeg in 180..189) lonDeg -= 80 else if (lonDeg in 190..199) lonDeg -= 190
        var lonMinInt = info[2].code - 28
        if (lonMinInt >= 60) lonMinInt -= 60
        val lonHundredths = info[3].code - 28
        if (lonDeg !in 0..180 || lonMinInt !in 0..59 || lonHundredths !in 0..99) return null
        val lon = (lonDeg + (lonMinInt + lonHundredths / 100.0) / 60.0).let { if (west) -it else it }

        val packet = newPacket(callsign, lat, lon, info[8], info[7], timestampMs) ?: return null

        val sp = info[4].code - 28
        val dc = info[5].code - 28
        val se = info[6].code - 28
        var speedKnots = sp * 10 + dc / 10
        var course = (dc % 10) * 100 + se
        if (speedKnots >= 800) speedKnots -= 800
        if (course >= 400) course -= 400
        if (speedKnots in 1..799) packet.speedKmh = speedKnots * KNOTS_TO_KMH
        if (course in 1..360) packet.courseDegrees = course % 360

        var comment = info.substring(9)
        // Wysokość: "xxx}" w base91, metry ponad -10 km; może ją poprzedzać bajt typu radia
        val altMatch = Regex("^[>\\]`']?([!-{]{3})\\}").find(comment)
        if (altMatch != null) {
            packet.altitudeM = base91(altMatch.groupValues[1]) - 10000.0
            comment = comment.substring(altMatch.range.last + 1)
        } else if (comment.isNotEmpty() && comment[0] in ">]`'") {
            comment = comment.substring(1)
        }
        // Kod producenta na końcu (np. "_\"" Yaesu, "=" Kenwood) – nie jest częścią komentarza
        comment = comment.replace(Regex("(_[ \"#$%&()*+,\\-./0-9:;<=>?@A-Z\\[\\]^_`{|}~]|[=^])$"), "")
        return finishPacket(packet, comment)
    }

    private fun base91(s: String): Long = s.fold(0L) { acc, ch -> acc * 91 + (ch.code - 33) }

    // Wspólne pola komentarza: PHG, wysokość "/A=", pogoda
    private fun finishPacket(packet: AprsPacket, rawComment: String): AprsPacket {
        var comment = rawComment
        val isWeather = packet.symbol == '_'

        if (comment.startsWith("PHG") && comment.length >= 7) {
            val p = comment[3].digitToIntOrNull()
            val h = comment[4].digitToIntOrNull()
            val g = comment[5].digitToIntOrNull()
            val d = comment[6].digitToIntOrNull()

            if (p != null && h != null && g != null && d != null) {
                packet.powerW = p * p
                packet.antennaHeightM = 10.0 * Math.pow(2.0, h.toDouble()) * FEET_TO_M
                packet.antennaGainDb = g
                packet.directivityDeg = d * 45
                comment = comment.substring(7)
            }
        }

        // Wysokość w stopach "/A=001234" – może wystąpić w dowolnym miejscu komentarza
        Regex("/A=(-?\\d{6})").find(comment)?.let {
            packet.altitudeM = it.groupValues[1].toInt() * FEET_TO_M
            comment = comment.removeRange(it.range)
        }

        // Pogoda (WX) – tylko dla stacji pogodowych, inaczej zwykły komentarz typu "QRV r145" byłby brany za opad
        if (isWeather) {
            comment = parseWeatherFields(comment, packet)
        }

        return packet.copy(comment = comment.trim())
    }

    // Czyta ciągły blok pól pogodowych z początku komentarza; zwraca resztę komentarza
    private fun parseWeatherFields(comment: String, packet: AprsPacket): String {
        var i = 0
        while (i < comment.length) {
            val key = comment[i]
            val length = WEATHER_FIELD_LENGTHS[key] ?: break
            if (i + 1 + length > comment.length) break
            val raw = comment.substring(i + 1, i + 1 + length)
            if (!raw.all { it.isDigit() || it == '-' || it == '.' || it == ' ' }) break

            // Brak pomiaru jest kodowany kropkami lub spacjami
            val value = raw.trim().toIntOrNull()
            if (value != null) {
                when (key) {
                    'c' -> packet.windDirDeg = value
                    's' -> packet.windSpeedKmh = value * MPH_TO_KMH
                    'g' -> packet.windGustKmh = value * MPH_TO_KMH
                    't' -> packet.tempCelsius = (value - 32.0) * 5.0 / 9.0
                    'r' -> packet.rain1hMm = value * HUNDREDTHS_INCH_TO_MM
                    'p' -> packet.rain24hMm = value * HUNDREDTHS_INCH_TO_MM
                    'P' -> packet.rainMidnightMm = value * HUNDREDTHS_INCH_TO_MM
                    'h' -> packet.humidityPercent = if (value == 0) 100 else value
                    'b' -> packet.pressureHpa = value / 10.0
                }
            }
            i += 1 + length
        }
        return comment.substring(i)
    }

    // "5224.50N" -> 52.408333, "01654.30E" -> 16.905
    private fun parseCoordinate(raw: String, degreeDigits: Int, negativeDir: Char): Double? {
        val degrees = raw.substring(0, degreeDigits).toDoubleOrNull() ?: return null
        val minutes = raw.substring(degreeDigits, degreeDigits + 5).toDoubleOrNull() ?: return null
        val value = degrees + minutes / 60.0
        return if (raw.last() == negativeDir) -value else value
    }
}
