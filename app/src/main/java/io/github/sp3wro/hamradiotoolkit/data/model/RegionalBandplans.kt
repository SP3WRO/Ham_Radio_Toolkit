package io.github.sp3wro.hamradiotoolkit.data.model

import io.github.sp3wro.hamradiotoolkit.data.geo.IaruRegion

/**
 * IARU Region 2 Band Plan, wrzesień 2020 (iaru.org). Uproszczony do najważniejszych segmentów;
 * oczywiste literówki dokumentu poprawione (np. "18095-15105" → 18.095–18.105).
 */
object Region2Bandplan {
    val bands = listOf(
        Band("160m", "1.800 - 2.000 MHz", listOf(
            BandSegment("1.800 - 1.810", "Digi", "Emisje cyfrowe (do 500 Hz)"),
            BandSegment("1.810 - 1.830", "CW", "CW; QRP 1.812"),
            BandSegment("1.830 - 1.840", "CW", "CW – okno DX (łączności międzykontynentalne)"),
            BandSegment("1.840 - 1.850", "Phone", "SSB – okno DX; Digi do 200 Hz w 1.840–1.843"),
            BandSegment("1.850 - 2.000", "Wszystkie", "SSB (LSB), AM; QRP 1.910")
        )),
        Band("80m", "3.500 - 4.000 MHz", listOf(
            BandSegment("3.500 - 3.510", "CW", "CW – okno DX"),
            BandSegment("3.510 - 3.570", "CW", "CW; zawody CW; QRS 3.555, QRP 3.560"),
            BandSegment("3.570 - 3.600", "CW, Digi", "Digi (FT8: 3.573); stacje automatyczne 3.590–3.600"),
            BandSegment("3.600 - 3.700", "Wszystkie", "SSB (LSB); zawody SSB; QRP 3.690"),
            BandSegment("3.700 - 3.775", "Wszystkie", "SSB; obraz 3.735; łączność kryzysowa 3.750"),
            BandSegment("3.775 - 3.800", "Wszystkie", "SSB – okno DX"),
            BandSegment("3.800 - 3.900", "Wszystkie", "SSB; obraz 3.845; AM 3.885"),
            BandSegment("3.900 - 4.000", "Wszystkie", "SSB; łączność kryzysowa 3.985")
        )),
        Band("60m", "5.3515 - 5.3665 MHz", listOf(
            BandSegment("5.3515 - 5.3540", "CW, Digi", "CW, Digi (do 500 Hz)"),
            BandSegment("5.3540 - 5.3660", "Wszystkie", "SSB (USB)"),
            BandSegment("5.3660 - 5.3665", "Digi", "Słabe sygnały (do 20 Hz)")
        )),
        Band("40m", "7.000 - 7.300 MHz", listOf(
            BandSegment("7.000 - 7.025", "CW", "CW – okno DX"),
            BandSegment("7.025 - 7.040", "CW", "CW; QRP 7.030"),
            BandSegment("7.040 - 7.050", "CW, Digi", "Digi (do 500 Hz); stacje automatyczne 7.047–7.050"),
            BandSegment("7.050 - 7.060", "Wszystkie", "Stacje automatyczne 7.050–7.053; łączność kryzysowa R2: 7.060"),
            BandSegment("7.060 - 7.100", "Wszystkie", "SSB (LSB); zawody SSB; DV 7.070; FT8 7.074; QRP 7.090"),
            BandSegment("7.100 - 7.200", "Wszystkie", "SSB, AM; zawody SSB; obraz 7.165"),
            BandSegment("7.200 - 7.300", "Wszystkie", "SSB, AM; łączność kryzysowa 7.240 i 7.275; QRP 7.285; AM 7.290")
        )),
        Band("30m", "10.100 - 10.150 MHz", listOf(
            BandSegment("10.100 - 10.130", "CW", "CW; QRP 10.116"),
            BandSegment("10.130 - 10.140", "CW, Digi", "Digi (do 500 Hz, FT8: 10.136); stacje automatyczne"),
            BandSegment("10.140 - 10.150", "CW, Digi", "Digi; bez beaconów CW")
        )),
        Band("20m", "14.000 - 14.350 MHz", listOf(
            BandSegment("14.000 - 14.025", "CW", "CW – okno DX"),
            BandSegment("14.025 - 14.070", "CW", "CW; zawody CW; QRS 14.055; QRP 14.060"),
            BandSegment("14.070 - 14.099", "CW, Digi", "Digi (FT8: 14.074); stacje automatyczne 14.089–14.099"),
            BandSegment("14.099 - 14.101", "CW", "Beacony IBP (NCDXF) – wyłącznie"),
            BandSegment("14.101 - 14.112", "Wszystkie", "Stacje automatyczne"),
            BandSegment("14.112 - 14.200", "Wszystkie", "SSB (USB); zawody SSB; okno DX 14.190–14.200"),
            BandSegment("14.200 - 14.300", "Wszystkie", "SSB; obraz 14.230; QRP i AM 14.285"),
            BandSegment("14.300 - 14.350", "Wszystkie", "Globalna częstotliwość kryzysowa 14.300")
        )),
        Band("17m", "18.068 - 18.168 MHz", listOf(
            BandSegment("18.068 - 18.095", "CW", "CW; QRP 18.086"),
            BandSegment("18.095 - 18.109", "CW, Digi", "Digi (FT8: 18.100); stacje automatyczne 18.105–18.109"),
            BandSegment("18.109 - 18.111", "CW", "Beacony IBP (NCDXF) – wyłącznie"),
            BandSegment("18.111 - 18.168", "Wszystkie", "SSB (USB); stacje automatyczne do 18.120; QRP 18.130; kryzysowa 18.160")
        )),
        Band("15m", "21.000 - 21.450 MHz", listOf(
            BandSegment("21.000 - 21.070", "CW", "CW; QRP 21.060"),
            BandSegment("21.070 - 21.110", "CW, Digi", "Digi (FT8: 21.074); stacje automatyczne 21.090–21.110"),
            BandSegment("21.110 - 21.149", "Wszystkie", "Digi, wąskie emisje (do 500 Hz od 21.120)"),
            BandSegment("21.149 - 21.151", "CW", "Beacony IBP (NCDXF) – wyłącznie"),
            BandSegment("21.151 - 21.450", "Wszystkie", "SSB (USB); QRP 21.285; obraz 21.340; kryzysowa 21.360; AM 21.380–21.450")
        )),
        Band("12m", "24.890 - 24.990 MHz", listOf(
            BandSegment("24.890 - 24.915", "CW", "CW; QRP 24.906"),
            BandSegment("24.915 - 24.929", "CW, Digi", "Digi (FT8: 24.915); stacje automatyczne 24.925–24.929"),
            BandSegment("24.929 - 24.931", "CW", "Beacony IBP (NCDXF) – wyłącznie"),
            BandSegment("24.931 - 24.990", "Wszystkie", "SSB (USB); QRP 24.950")
        )),
        Band("10m", "28.000 - 29.700 MHz", listOf(
            BandSegment("28.000 - 28.070", "CW", "CW; QRS 28.055; QRP 28.060"),
            BandSegment("28.070 - 28.190", "CW, Digi", "Digi (FT8: 28.074); stacje automatyczne 28.120–28.150"),
            BandSegment("28.190 - 28.225", "CW", "Beacony; IBP 28.199–28.201"),
            BandSegment("28.225 - 28.300", "Wszystkie", "Beacony"),
            BandSegment("28.300 - 29.000", "Wszystkie", "SSB (USB); DV 28.330; QRP 28.360; obraz 28.680"),
            BandSegment("29.000 - 29.200", "Wszystkie", "AM"),
            BandSegment("29.200 - 29.300", "Wszystkie", "Stacje automatyczne"),
            BandSegment("29.300 - 29.510", "Sat", "Satelity"),
            BandSegment("29.520 - 29.590", "FM", "Wejścia przemienników"),
            BandSegment("29.590 - 29.620", "FM", "FM simplex – wywołanie 29.600"),
            BandSegment("29.620 - 29.700", "FM", "Wyjścia przemienników")
        )),
        Band("6m", "50 - 54 MHz", listOf(
            BandSegment("50.000 - 50.100", "CW", "Beacony"),
            BandSegment("50.100 - 50.125", "CW, SSB", "Okno DX; wywołanie 50.110"),
            BandSegment("50.125 - 50.400", "CW, SSB, Digi", "Słabe sygnały (FT8: 50.313)"),
            BandSegment("50.400 - 51.000", "Wszystkie", "Beacony cyfrowe, stacje automatyczne, zdalne sterowanie"),
            BandSegment("51.000 - 51.110", "CW, SSB", "Okno DX"),
            BandSegment("51.120 - 51.480", "FM", "Wejścia przemienników (wyjście +500 kHz)"),
            BandSegment("51.500 - 51.600", "FM", "FM simplex"),
            BandSegment("51.620 - 51.980", "FM", "Wyjścia przemienników"),
            BandSegment("52.000 - 52.100", "FM", "Bramki internetowe (IVG)"),
            BandSegment("52.100 - 54.000", "Wszystkie", "Wszystkie emisje")
        )),
        Band("2m", "144 - 148 MHz", listOf(
            BandSegment("144.000 - 144.110", "CW", "EME i słabe sygnały; satelity 144.000–144.025"),
            BandSegment("144.110 - 144.180", "CW, Digi", "EME, słabe sygnały (FT8: 144.174)"),
            BandSegment("144.180 - 144.275", "CW, SSB", "Słabe sygnały; wywołanie 144.200"),
            BandSegment("144.275 - 144.300", "CW", "Beacony"),
            BandSegment("144.300 - 144.360", "CW, SSB", "Wywołanie 144.300"),
            BandSegment("144.360 - 144.400", "Digi", "APRS 144.390, stacje automatyczne"),
            BandSegment("144.400 - 144.500", "CW, Digi", "Beacony (ISS: 144.490)"),
            BandSegment("144.600 - 144.900", "FM", "Wejścia przemienników (wyjście +600 kHz)"),
            BandSegment("145.000 - 145.100", "Wszystkie", "Stacje automatyczne, bramki internetowe"),
            BandSegment("145.200 - 145.500", "FM", "Wyjścia przemienników (wejście −600 kHz)"),
            BandSegment("145.500 - 145.790", "Wszystkie", "Wszystkie emisje"),
            BandSegment("145.800 - 146.000", "Sat", "Satelity – wyłącznie"),
            BandSegment("146.000 - 146.390", "FM", "Wejścia przemienników (wyjście +600 kHz)"),
            BandSegment("146.390 - 146.600", "FM", "FM simplex – wywołanie 146.520"),
            BandSegment("146.600 - 146.990", "FM", "Wyjścia przemienników (wejście −600 kHz)"),
            BandSegment("146.990 - 147.400", "FM", "Wejścia przemienników (wyjście +600 kHz)"),
            BandSegment("147.400 - 147.590", "FM", "FM i DV simplex"),
            BandSegment("147.590 - 148.000", "FM", "Wyjścia przemienników (wejście −600 kHz)")
        )),
        Band("1,25m", "220 - 225 MHz", listOf(
            BandSegment("220.000 - 222.000", "Wszystkie", "Stacje automatyczne"),
            BandSegment("222.000 - 222.150", "CW, SSB", "Słabe sygnały; wywołanie 222.100"),
            BandSegment("222.250 - 223.380", "FM", "Wejścia przemienników (wyjście +1,6 MHz)"),
            BandSegment("223.380 - 223.520", "FM", "FM simplex"),
            BandSegment("223.850 - 225.000", "FM", "Wyjścia przemienników")
        )),
        Band("70cm", "420 - 450 MHz", listOf(
            BandSegment("420.000 - 432.000", "Wszystkie", "ATV, łącza (wg krajowych przydziałów)"),
            BandSegment("432.000 - 432.100", "CW, Digi", "EME i słabe sygnały"),
            BandSegment("432.100 - 432.300", "CW, SSB", "Słabe sygnały; wywołanie 432.100"),
            BandSegment("432.300 - 432.420", "CW", "Beacony"),
            BandSegment("432.420 - 433.000", "CW, SSB, Digi", "Wąskopasmowe"),
            BandSegment("433.000 - 433.100", "Digi", "Stacje automatyczne, bramki internetowe"),
            BandSegment("433.100 - 435.000", "Wszystkie", "Opcja lokalna"),
            BandSegment("435.000 - 438.000", "Sat", "Satelity – wyłącznie"),
            BandSegment("438.000 - 450.000", "FM", "Opcja lokalna – zwykle przemienniki (wg planów krajowych)")
        )),
        Band("23cm", "1240 - 1300 MHz", listOf(
            BandSegment("1240 - 1246", "Wszystkie", "ATV kanał 1"),
            BandSegment("1260 - 1270", "Sat", "Satelity (uplink), eksperymenty"),
            BandSegment("1270 - 1276", "FM", "Wejścia przemienników"),
            BandSegment("1282 - 1288", "FM", "Wyjścia przemienników"),
            BandSegment("1294.000 - 1295.000", "FM", "FM simplex – wywołanie 1294.500"),
            BandSegment("1295.800 - 1296.080", "CW, SSB, Digi", "EME i słabe sygnały"),
            BandSegment("1296.080 - 1296.200", "CW, SSB", "Słabe sygnały; wywołanie 1296.100"),
            BandSegment("1296.200 - 1296.400", "CW, Digi", "Beacony")
        ))
    )
}

/** IARU Region 3 Band Plan, dokument R3-004 Rev. 2, listopad 2024 (iaru-r3.org). */
object Region3Bandplan {
    val bands = listOf(
        Band("160m", "1.800 - 2.000 MHz", listOf(
            BandSegment("1.800 - 1.830", "CW", "CW"),
            BandSegment("1.830 - 1.840", "CW, Digi", "CW i wąskie emisje; QRP 1.836"),
            BandSegment("1.840 - 2.000", "Wszystkie", "Wszystkie emisje (FT8: 1.840)")
        )),
        Band("80m", "3.500 - 3.900 MHz", listOf(
            BandSegment("3.500 - 3.510", "CW", "CW – DX"),
            BandSegment("3.510 - 3.535", "CW", "CW"),
            BandSegment("3.535 - 3.600", "Wszystkie", "CW, fonia, Digi; QRP 3.560; FT8 3.573"),
            BandSegment("3.600 - 3.775", "Wszystkie", "Kryzysowa 3.600; DV i QRP SSB 3.690; obraz 3.735"),
            BandSegment("3.775 - 3.800", "Phone", "CW, fonia – DX (3.795)"),
            BandSegment("3.800 - 3.900", "Wszystkie", "CW, fonia, Digi; obraz 3.845")
        )),
        Band("60m", "5.3515 - 5.3665 MHz", listOf(
            BandSegment("5.3515 - 5.3540", "CW, Digi", "CW, wąskie emisje"),
            BandSegment("5.3540 - 5.3660", "Wszystkie", "Wszystkie – fonia na USB"),
            BandSegment("5.3660 - 5.3665", "Digi", "Słabe sygnały (do 20 Hz)")
        )),
        Band("40m", "7.000 - 7.200 MHz", listOf(
            BandSegment("7.000 - 7.030", "CW", "CW"),
            BandSegment("7.030 - 7.040", "CW, Digi", "CW, wąskie emisje, fonia; QRP 7.030; RTTY DX 7.040"),
            BandSegment("7.040 - 7.060", "Wszystkie", "Wszystkie, wąskie emisje, fonia"),
            BandSegment("7.060 - 7.100", "Wszystkie", "DV 7.070; FT8 7.074; QRP SSB 7.090; DX fonia 7.095"),
            BandSegment("7.100 - 7.200", "Wszystkie", "Łączność kryzysowa 7.110; obraz 7.165")
        )),
        Band("30m", "10.100 - 10.150 MHz", listOf(
            BandSegment("10.100 - 10.130", "CW", "CW; QRP 10.116"),
            BandSegment("10.130 - 10.150", "CW, Digi", "Wąskie emisje (FT8: 10.136); niektóre kraje dopuszczają fonię 10.115–10.140")
        )),
        Band("20m", "14.000 - 14.350 MHz", listOf(
            BandSegment("14.000 - 14.070", "CW", "CW; QRS 14.055; QRP 14.060"),
            BandSegment("14.070 - 14.099", "CW, Digi", "Wąskie emisje (FT8: 14.074)"),
            BandSegment("14.099 - 14.101", "CW", "Beacony IBP (NCDXF)"),
            BandSegment("14.101 - 14.112", "Wszystkie", "CW, Digi, fonia"),
            BandSegment("14.112 - 14.350", "Phone", "CW, fonia; DV 14.130; DX fonia 14.195; obraz 14.230; QRP 14.285; kryzysowa 14.300")
        )),
        Band("17m", "18.068 - 18.168 MHz", listOf(
            BandSegment("18.068 - 18.095", "CW", "CW; QRP 18.086"),
            BandSegment("18.095 - 18.109", "CW, Digi", "Wąskie emisje (FT8: 18.100)"),
            BandSegment("18.109 - 18.111", "CW", "Beacony IBP (NCDXF)"),
            BandSegment("18.111 - 18.120", "Wszystkie", "CW, wąskie emisje, fonia"),
            BandSegment("18.120 - 18.168", "Phone", "CW, fonia; QRP SSB 18.130; DV i kryzysowa 18.160")
        )),
        Band("15m", "21.000 - 21.450 MHz", listOf(
            BandSegment("21.000 - 21.070", "CW", "CW; QRS 21.055; QRP 21.060"),
            BandSegment("21.070 - 21.110", "CW, Digi", "Wąskie emisje (FT8: 21.074)"),
            BandSegment("21.110 - 21.149", "CW, Digi", "Wąskie emisje; od 21.125 także uplink satelitów"),
            BandSegment("21.149 - 21.151", "CW", "Beacony IBP (NCDXF)"),
            BandSegment("21.151 - 21.450", "Phone", "CW, fonia; DV 21.180; DX 21.295; obraz 21.340; kryzysowa 21.360")
        )),
        Band("12m", "24.890 - 24.990 MHz", listOf(
            BandSegment("24.890 - 24.915", "CW", "CW; QRP 24.906"),
            BandSegment("24.915 - 24.929", "CW, Digi", "Wąskie emisje (FT8: 24.915)"),
            BandSegment("24.929 - 24.931", "CW", "Beacony IBP (NCDXF)"),
            BandSegment("24.931 - 24.940", "Wszystkie", "CW, wąskie emisje, DX, fonia"),
            BandSegment("24.940 - 24.990", "Phone", "CW, fonia; QRP SSB 24.950; Digi 24.960")
        )),
        Band("10m", "28.000 - 29.700 MHz", listOf(
            BandSegment("28.000 - 28.070", "CW", "CW; QRS 28.055"),
            BandSegment("28.070 - 28.190", "CW, Digi", "Wąskie emisje (FT8: 28.074); DX 28.150–28.190"),
            BandSegment("28.190 - 28.225", "CW", "Beacony; IBP 28.200"),
            BandSegment("28.225 - 29.100", "Wszystkie", "DV 28.330; QRP 28.360; DX 28.495; obraz 28.680"),
            BandSegment("29.100 - 29.300", "Wszystkie", "Wszystkie emisje (do 6 kHz)"),
            BandSegment("29.300 - 29.510", "Sat", "Satelity (uplink i downlink)"),
            BandSegment("29.510 - 29.590", "FM", "Wejścia przemienników FM"),
            BandSegment("29.590 - 29.620", "FM", "FM simplex – międzynarodowe wywołanie 29.600"),
            BandSegment("29.620 - 29.700", "FM", "Wyjścia przemienników FM")
        )),
        Band("6m", "50 - 54 MHz", listOf(
            BandSegment("50.000 - 50.100", "CW", "CW; beacony międzynarodowe 50.020–50.080"),
            BandSegment("50.100 - 50.500", "CW, SSB, Digi", "CW, fonia, Digi; DX 50.110; FT8 50.313"),
            BandSegment("50.500 - 54.000", "Wszystkie", "Wszystkie emisje, FM")
        )),
        Band("2m", "144 - 148 MHz", listOf(
            BandSegment("144.000 - 144.025", "Digi", "Satelity i wąskie emisje"),
            BandSegment("144.025 - 144.035", "CW", "EME, słabe sygnały"),
            BandSegment("144.035 - 145.800", "Wszystkie", "Wszystkie emisje; DX 144.100; APRS 144.800 (zalecana)"),
            BandSegment("145.800 - 146.000", "Sat", "Satelity (APRS satelitarny 145.825)"),
            BandSegment("146.000 - 148.000", "Wszystkie", "Wszystkie emisje (wg planów krajowych)")
        )),
        Band("70cm", "430 - 440 MHz", listOf(
            BandSegment("430.000 - 431.900", "Wszystkie", "Wszystkie emisje"),
            BandSegment("431.900 - 432.240", "CW, SSB", "EME, słabe sygnały"),
            BandSegment("432.240 - 435.000", "Wszystkie", "Wszystkie emisje"),
            BandSegment("435.000 - 438.000", "Sat", "Satelity"),
            BandSegment("438.000 - 440.000", "Wszystkie", "Wszystkie emisje")
        )),
        Band("23cm", "1240 - 1300 MHz", listOf(
            BandSegment("1240 - 1260", "Wszystkie", "Wszystkie emisje"),
            BandSegment("1260 - 1270", "Sat", "Satelity – tylko kierunek Ziemia–kosmos"),
            BandSegment("1270 - 1296", "Wszystkie", "Wszystkie emisje"),
            BandSegment("1296 - 1297", "CW, SSB, Digi", "EME, słabe sygnały"),
            BandSegment("1297 - 1300", "Wszystkie", "Wszystkie emisje")
        ))
    )
}

/** Najważniejsze częstotliwości "na co dzień" dla regionu IARU (i wyjątki krajowe). */
data class RegionalFrequencies(
    val aprs: String,
    val fmCalling: String,
    val emergency: String,
    val source: String
)

object RegionalInfo {
    fun forRegion(region: IaruRegion, iso: String? = null): RegionalFrequencies = when (region) {
        IaruRegion.R1 -> RegionalFrequencies(
            aprs = "144.800 MHz",
            fmCalling = "145.500 MHz (2 m), 433.500 MHz (70 cm)",
            emergency = "3.760, 7.110, 14.300, 18.160, 21.360 MHz",
            source = "IARU Region 1"
        )
        IaruRegion.R2 -> RegionalFrequencies(
            aprs = "144.390 MHz" + if (iso != null && iso in CARIBBEAN) " (Karaiby: chroniona też 145.010)" else "",
            fmCalling = "146.520 MHz (2 m), 29.600 MHz (10 m)",
            emergency = "3.750, 3.985, 7.060, 7.240, 7.275, 14.300, 18.160, 21.360 MHz",
            source = "IARU Region 2 Band Plan (09.2020)"
        )
        IaruRegion.R3 -> RegionalFrequencies(
            aprs = when (iso) {
                // R3-004: 144.390 – MARTS, RAST, SARTS, ORARI; 144.640 – CRAC, CTARL, HARTS
                "MY", "TH", "SG", "ID" -> "144.390 MHz"
                "CN", "TW", "HK" -> "144.640 MHz"
                else -> "144.800 MHz (zalecana; część krajów używa innej – sprawdź krajowy bandplan)"
            },
            fmCalling = "29.600 MHz (10 m); na UKF wg planów krajowych",
            emergency = "3.600, 7.110, 14.300, 18.160, 21.360 MHz",
            source = "IARU Region 3, R3-004 Rev. 2 (11.2024)"
        )
    }

    private val CARIBBEAN = setOf(
        "CU", "JM", "HT", "DO", "PR", "VI", "VG", "AI", "KN", "AG", "MS", "GP", "DM", "MQ", "LC", "VC", "BB",
        "GD", "TT", "AW", "CW", "BQ", "SX", "MF", "BL", "KY", "TC", "BS"
    )
}
