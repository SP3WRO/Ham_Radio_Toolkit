package io.github.sp3wro.hamradiotoolkit.data.model

data class BandSegment(
    val range: String,
    val mode: String,
    val description: String
)

data class Band(
    val name: String,
    val range: String,
    val segments: List<BandSegment>
)

object BandplanProvider {
    fun bandsFor(region: io.github.sp3wro.hamradiotoolkit.data.geo.IaruRegion): List<Band> = when (region) {
        io.github.sp3wro.hamradiotoolkit.data.geo.IaruRegion.R1 -> bands
        io.github.sp3wro.hamradiotoolkit.data.geo.IaruRegion.R2 -> Region2Bandplan.bands
        io.github.sp3wro.hamradiotoolkit.data.geo.IaruRegion.R3 -> Region3Bandplan.bands
    }

    /** Region 1 (z uwzględnieniem praktyki w Polsce). */
    val bands = listOf(
        Band("160m", "1.810 - 2.000 MHz", listOf(
            BandSegment("1.810 - 1.838", "CW", "Tylko CW"),
            BandSegment("1.838 - 1.840", "Wąskopasmowe", "Emisje cyfrowe (Digi)"),
            BandSegment("1.840 - 1.843", "Wszystkie", "Emisje cyfrowe (Digi), CW, Phone"),
            BandSegment("1.843 - 2.000", "Phone", "SSB (LSB), CW")
        )),
        Band("80m", "3.500 - 3.800 MHz", listOf(
            BandSegment("3.500 - 3.570", "CW", "Tylko CW"),
            BandSegment("3.570 - 3.580", "Wąskopasmowe", "Digi (FT8: 3.573)"),
            BandSegment("3.580 - 3.600", "Wszystkie", "Digi (szerokopasmowe)"),
            BandSegment("3.600 - 3.800", "Phone", "SSB (LSB), Phone Contest")
        )),
        Band("60m", "5.3515 - 5.3665 MHz", listOf(
            BandSegment("5.3515 - 5.3540", "CW, Wąskopasmowe", "Tylko CW, Digi"),
            BandSegment("5.3540 - 5.3660", "Wszystkie", "SSB (USB)"),
            BandSegment("5.3660 - 5.3665", "Wąskopasmowe", "Słabe sygnały (Digi)")
        )),
        Band("40m", "7.000 - 7.200 MHz", listOf(
            BandSegment("7.000 - 7.040", "CW", "Tylko CW"),
            BandSegment("7.040 - 7.047", "Wąskopasmowe", "Digi (FT8: 7.041, FT4: 7.047)"),
            BandSegment("7.047 - 7.050", "Wszystkie", "Digi, automatyczne stacje"),
            BandSegment("7.050 - 7.200", "Phone", "SSB (LSB)")
        )),
        Band("30m", "10.100 - 10.150 MHz", listOf(
            BandSegment("10.100 - 10.130", "CW", "Tylko CW"),
            BandSegment("10.130 - 10.150", "Wąskopasmowe", "Digi (FT8: 10.136), brak SSB!")
        )),
        Band("20m", "14.000 - 14.350 MHz", listOf(
            BandSegment("14.000 - 14.070", "CW", "Tylko CW"),
            BandSegment("14.070 - 14.089", "Wąskopasmowe", "Digi (FT8: 14.074)"),
            BandSegment("14.089 - 14.099", "Wąskopasmowe", "Zautomatyzowane stacje"),
            BandSegment("14.099 - 14.101", "Wszystkie", "Beacony (NCDXF)"),
            BandSegment("14.101 - 14.350", "Phone", "SSB (USB), SSTV (14.230)")
        )),
        Band("17m", "18.068 - 18.168 MHz", listOf(
            BandSegment("18.068 - 18.095", "CW", "Tylko CW"),
            BandSegment("18.095 - 18.109", "Wąskopasmowe", "Digi (FT8: 18.100)"),
            BandSegment("18.109 - 18.111", "Wszystkie", "Beacony (NCDXF)"),
            BandSegment("18.111 - 18.168", "Phone", "SSB (USB)")
        )),
        Band("15m", "21.000 - 21.450 MHz", listOf(
            BandSegment("21.000 - 21.070", "CW", "Tylko CW"),
            BandSegment("21.070 - 21.110", "Wąskopasmowe", "Digi (FT8: 21.074)"),
            BandSegment("21.110 - 21.120", "Wszystkie", "Beacony"),
            BandSegment("21.120 - 21.149", "Wąskopasmowe", "Digi"),
            BandSegment("21.149 - 21.151", "Wszystkie", "Beacony (NCDXF)"),
            BandSegment("21.151 - 21.450", "Phone", "SSB (USB)")
        )),
        Band("12m", "24.890 - 24.990 MHz", listOf(
            BandSegment("24.890 - 24.915", "CW", "Tylko CW"),
            BandSegment("24.915 - 24.929", "Wąskopasmowe", "Digi (FT8: 24.915)"),
            BandSegment("24.929 - 24.931", "Wszystkie", "Beacony (NCDXF)"),
            BandSegment("24.931 - 24.990", "Phone", "SSB (USB)")
        )),
        Band("10m", "28.000 - 29.700 MHz", listOf(
            BandSegment("28.000 - 28.070", "CW", "Tylko CW"),
            BandSegment("28.070 - 28.190", "Wąskopasmowe", "Digi (FT8: 28.074)"),
            BandSegment("28.190 - 28.225", "Wszystkie", "Beacony (regionalne i lokalne)"),
            BandSegment("28.225 - 29.200", "Phone", "SSB (USB), SSTV (28.680)"),
            BandSegment("29.200 - 29.300", "Wszystkie", "Digi (Szerokopasmowe), NBFM"),
            BandSegment("29.300 - 29.510", "Sat", "Satelity (Downlink)"),
            BandSegment("29.510 - 29.700", "FM", "FM Simplex i Przemienniki (wejścia i wyjścia)")
        )),
        Band("2m", "144 - 146 MHz", listOf(
            BandSegment("144.000 - 144.025", "Sat", "Satelity"),
            BandSegment("144.025 - 144.110", "CW", "CW EME i wywołanie (144.050)"),
            BandSegment("144.110 - 144.150", "CW, Digi", "Telegrafia i MGM"),
            BandSegment("144.150 - 144.400", "Wszystkie", "SSB, CW, MGM (Wywołanie SSB: 144.300, FT8: 144.174)"),
            BandSegment("144.400 - 144.490", "Wszystkie", "Beacony"),
            BandSegment("144.500 - 144.794", "Wszystkie", "SSTV, ATV, Cyfrowe, FM Simplex"),
            BandSegment("144.800", "Digi", "APRS (Europa)"),
            BandSegment("145.000 - 145.1875", "FM", "Wejścia przemienników FM"),
            BandSegment("145.200 - 145.5875", "FM", "FM Simplex (Wywołanie: 145.500)"),
            BandSegment("145.600 - 145.7875", "FM", "Wyjścia przemienników FM")
        )),
        Band("70cm", "430 - 440 MHz", listOf(
            BandSegment("430.000 - 431.975", "Wszystkie", "Sub-band komunikacji lokalnej, przemienniki"),
            BandSegment("432.000 - 432.100", "CW", "CW EME, Wywołanie CW"),
            BandSegment("432.100 - 432.400", "Wszystkie", "SSB, CW, MGM (Wywołanie SSB: 432.200, FT8: 432.174)"),
            BandSegment("432.400 - 432.490", "Wszystkie", "Beacony"),
            BandSegment("432.500 - 433.000", "Wszystkie", "SSTV, ATV, Cyfrowe, FM Simplex"),
            BandSegment("433.000 - 433.375", "FM", "Wejścia przemienników FM / Simplex"),
            BandSegment("433.400 - 433.575", "FM", "FM Simplex (Wywołanie: 433.500)"),
            BandSegment("434.600 - 434.975", "FM", "Wyjścia przemienników FM"),
            BandSegment("435.000 - 438.000", "Sat", "Satelity (Sub-band amatorski)")
        )),
        Band("23cm", "1240 - 1300 MHz", listOf(
            BandSegment("1240 - 1243", "Wszystkie", "ATV, Cyfrowe"),
            BandSegment("1260 - 1270", "Sat", "Satelity (Uplink)"),
            BandSegment("1296.000 - 1296.150", "CW, Digi", "CW EME, Wywołanie CW"),
            BandSegment("1296.150 - 1296.800", "Wszystkie", "SSB, CW, MGM (Wywołanie SSB: 1296.200)"),
            BandSegment("1296.800 - 1296.994", "Wszystkie", "Beacony"),
            BandSegment("1297.000 - 1297.475", "FM", "Wyjścia przemienników FM"),
            BandSegment("1297.500 - 1297.975", "FM", "FM Simplex (Wywołanie: 1297.500)")
        ))
    )
}