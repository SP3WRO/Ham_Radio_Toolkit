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

    /**
     * Region 1 według aktualnych dokumentów IARU R1 (sprawdzone 2026-11-14):
     * KF – "IARU Region 1 HF Band Plan", obowiązuje od 16.10.2020 (Novi Sad, bez późniejszych zmian);
     * UKF i 23 cm – "IARU-R1 VHF Handbook" wersja 10.03, luty 2026.
     * Uproszczony do najważniejszych segmentów; częstotliwości FT8/FT4 to przyjęta praktyka, nie część planu.
     */
    val bands = listOf(
        Band("160m", "1.810 - 2.000 MHz", listOf(
            BandSegment("1.810 - 1.838", "CW", "Tylko CW; QRP 1.836"),
            BandSegment("1.838 - 1.840", "Wąskopasmowe", "Wąskie emisje (do 500 Hz)"),
            BandSegment("1.840 - 1.843", "Wszystkie", "Emisje cyfrowe (FT8: 1.840)"),
            BandSegment("1.843 - 2.000", "Phone", "Fonia (LSB), CW")
        )),
        Band("80m", "3.500 - 3.800 MHz", listOf(
            BandSegment("3.500 - 3.510", "CW", "Tylko CW; łączności międzykontynentalne"),
            BandSegment("3.510 - 3.560", "CW", "Tylko CW; zawody CW; QRS 3.555"),
            BandSegment("3.560 - 3.570", "CW", "Tylko CW; QRP 3.560"),
            BandSegment("3.570 - 3.580", "Wąskopasmowe", "Emisje cyfrowe (do 200 Hz; FT8: 3.573)"),
            BandSegment("3.580 - 3.590", "Wąskopasmowe", "Emisje cyfrowe (do 500 Hz)"),
            BandSegment("3.590 - 3.600", "Wąskopasmowe", "Emisje cyfrowe, stacje automatyczne"),
            BandSegment("3.600 - 3.620", "Wszystkie", "Emisje cyfrowe, stacje automatyczne; zawody SSB; fonia od 3.603 (LSB)"),
            BandSegment("3.620 - 3.650", "Phone", "Fonia (LSB); zawody SSB; DV 3.630"),
            BandSegment("3.650 - 3.700", "Phone", "Fonia (LSB); QRP 3.690"),
            BandSegment("3.700 - 3.775", "Phone", "Fonia (LSB); zawody SSB; obraz 3.735; łączność kryzysowa 3.760"),
            BandSegment("3.775 - 3.800", "Phone", "Fonia (LSB); zawody SSB; łączności międzykontynentalne")
        )),
        Band("60m", "5.3515 - 5.3665 MHz", listOf(
            BandSegment("5.3515 - 5.3540", "CW, Wąskopasmowe", "CW, wąskie emisje"),
            BandSegment("5.3540 - 5.3660", "Wszystkie", "Fonia na USB (do 5.363)"),
            BandSegment("5.3660 - 5.3665", "Wąskopasmowe", "Słabe sygnały (do 20 Hz)")
        )),
        Band("40m", "7.000 - 7.200 MHz", listOf(
            BandSegment("7.000 - 7.040", "CW", "Tylko CW; QRP 7.030"),
            BandSegment("7.040 - 7.047", "Wąskopasmowe", "Emisje cyfrowe (do 500 Hz)"),
            BandSegment("7.047 - 7.050", "Wąskopasmowe", "Emisje cyfrowe, stacje automatyczne (FT4: 7.0475)"),
            BandSegment("7.050 - 7.053", "Wszystkie", "Emisje cyfrowe, stacje automatyczne"),
            BandSegment("7.053 - 7.060", "Wszystkie", "Emisje cyfrowe; fonia od 7.053 (LSB)"),
            BandSegment("7.060 - 7.100", "Phone", "Fonia (LSB); zawody SSB; DV 7.070; FT8 7.074; QRP 7.090"),
            BandSegment("7.100 - 7.130", "Phone", "Fonia (LSB); łączność kryzysowa 7.110"),
            BandSegment("7.130 - 7.175", "Phone", "Fonia (LSB); zawody SSB; obraz 7.165"),
            BandSegment("7.175 - 7.200", "Phone", "Fonia (LSB); zawody SSB; łączności międzykontynentalne")
        )),
        Band("30m", "10.100 - 10.150 MHz", listOf(
            BandSegment("10.100 - 10.130", "CW", "Tylko CW; QRP 10.116"),
            BandSegment("10.130 - 10.150", "Wąskopasmowe", "Emisje cyfrowe (FT8: 10.136), brak SSB!")
        )),
        Band("20m", "14.000 - 14.350 MHz", listOf(
            BandSegment("14.000 - 14.060", "CW", "Tylko CW; zawody CW; QRS 14.055"),
            BandSegment("14.060 - 14.070", "CW", "Tylko CW; QRP 14.060"),
            BandSegment("14.070 - 14.089", "Wąskopasmowe", "Emisje cyfrowe (FT8: 14.074, FT4: 14.080)"),
            BandSegment("14.089 - 14.099", "Wąskopasmowe", "Emisje cyfrowe, stacje automatyczne"),
            BandSegment("14.099 - 14.101", "Beacony", "Beacony międzynarodowe (NCDXF/IBP), wyłącznie"),
            BandSegment("14.101 - 14.112", "Wszystkie", "Emisje cyfrowe, stacje automatyczne"),
            BandSegment("14.112 - 14.125", "Wszystkie", "Wszystkie emisje"),
            BandSegment("14.125 - 14.300", "Phone", "Fonia (USB); zawody SSB; DV 14.130; DX 14.195 ±5; obraz 14.230; QRP 14.285"),
            BandSegment("14.300 - 14.350", "Phone", "Fonia (USB); globalna częstotliwość kryzysowa 14.300")
        )),
        Band("17m", "18.068 - 18.168 MHz", listOf(
            BandSegment("18.068 - 18.095", "CW", "Tylko CW; QRP 18.086"),
            BandSegment("18.095 - 18.105", "Wąskopasmowe", "Emisje cyfrowe (FT8: 18.100)"),
            BandSegment("18.105 - 18.109", "Wąskopasmowe", "Emisje cyfrowe, stacje automatyczne"),
            BandSegment("18.109 - 18.111", "Beacony", "Beacony międzynarodowe (NCDXF/IBP), wyłącznie"),
            BandSegment("18.111 - 18.120", "Wszystkie", "Emisje cyfrowe, stacje automatyczne"),
            BandSegment("18.120 - 18.168", "Phone", "Fonia (USB); QRP 18.130; DV 18.150; łączność kryzysowa 18.160")
        )),
        Band("15m", "21.000 - 21.450 MHz", listOf(
            BandSegment("21.000 - 21.070", "CW", "Tylko CW; QRS 21.055; QRP 21.060"),
            BandSegment("21.070 - 21.090", "Wąskopasmowe", "Emisje cyfrowe (FT8: 21.074)"),
            BandSegment("21.090 - 21.110", "Wąskopasmowe", "Emisje cyfrowe, stacje automatyczne"),
            BandSegment("21.110 - 21.120", "Wszystkie", "Emisje cyfrowe, stacje automatyczne; brak SSB!"),
            BandSegment("21.120 - 21.149", "Wąskopasmowe", "Wąskie emisje (do 500 Hz)"),
            BandSegment("21.149 - 21.151", "Beacony", "Beacony międzynarodowe (NCDXF/IBP), wyłącznie"),
            BandSegment("21.151 - 21.450", "Phone", "Fonia (USB); DV 21.180; QRP 21.285; obraz 21.340; globalna częstotliwość kryzysowa 21.360")
        )),
        Band("12m", "24.890 - 24.990 MHz", listOf(
            BandSegment("24.890 - 24.915", "CW", "Tylko CW; QRP 24.906"),
            BandSegment("24.915 - 24.925", "Wąskopasmowe", "Emisje cyfrowe (FT8: 24.915)"),
            BandSegment("24.925 - 24.929", "Wąskopasmowe", "Emisje cyfrowe, stacje automatyczne"),
            BandSegment("24.929 - 24.931", "Beacony", "Beacony międzynarodowe (NCDXF/IBP), wyłącznie"),
            BandSegment("24.931 - 24.940", "Wszystkie", "Emisje cyfrowe, stacje automatyczne"),
            BandSegment("24.940 - 24.990", "Phone", "Fonia (USB); QRP 24.950; DV 24.960")
        )),
        Band("10m", "28.000 - 29.700 MHz", listOf(
            BandSegment("28.000 - 28.070", "CW", "Tylko CW; QRS 28.055; QRP 28.060"),
            BandSegment("28.070 - 28.120", "Wąskopasmowe", "Emisje cyfrowe (FT8: 28.074)"),
            BandSegment("28.120 - 28.150", "Wąskopasmowe", "Emisje cyfrowe, stacje automatyczne"),
            BandSegment("28.150 - 28.190", "Wąskopasmowe", "Wąskie emisje (FT4: 28.180)"),
            BandSegment("28.190 - 28.225", "Beacony", "Beacony międzynarodowe (IBP), wyłącznie"),
            BandSegment("28.225 - 28.300", "Wszystkie", "Beacony regionalne i lokalne"),
            BandSegment("28.300 - 28.320", "Wszystkie", "Emisje cyfrowe, stacje automatyczne"),
            BandSegment("28.320 - 29.000", "Phone", "Fonia (USB); DV 28.330; QRP 28.360; obraz 28.680"),
            BandSegment("29.000 - 29.100", "Wszystkie", "Wszystkie emisje (AM)"),
            BandSegment("29.100 - 29.200", "FM", "FM simplex (10 kHz)"),
            BandSegment("29.200 - 29.300", "Wszystkie", "Emisje cyfrowe, stacje automatyczne"),
            BandSegment("29.300 - 29.510", "Sat", "Satelity"),
            BandSegment("29.520 - 29.590", "FM", "Wejścia przemienników (RH1–RH8)"),
            BandSegment("29.600", "FM", "FM simplex – wywołanie"),
            BandSegment("29.620 - 29.700", "FM", "Wyjścia przemienników (RH1–RH8)")
        )),
        Band("6m", "50 - 54 MHz", listOf(
            BandSegment("50.000 - 50.100", "CW", "Tylko CW; beacony 50.000–50.030; łączności międzykontynentalne 50.090"),
            BandSegment("50.100 - 50.200", "CW, SSB", "Okno DX 50.100–50.130 (międzynarodowe wywołanie 50.110); wywołanie 50.150"),
            BandSegment("50.200 - 50.300", "CW, SSB", "SSB, CW; 50.285 crossband"),
            BandSegment("50.300 - 50.400", "Wąskopasmowe", "Emisje cyfrowe (FT8: 50.313, FT4: 50.318); EME 50.310–50.320; MS 50.320–50.380"),
            BandSegment("50.400 - 50.500", "Beacony", "Beacony, wyłącznie"),
            BandSegment("50.500 - 50.700", "Wszystkie", "Emisje cyfrowe; DV 50.630; bramki internetowe 50.540–50.580"),
            BandSegment("50.700 - 50.900", "FM", "Wyjścia przemienników (–500 kHz)"),
            BandSegment("50.900 - 51.200", "Wszystkie", "Cyfrowe szerokopasmowe – eksperymenty"),
            BandSegment("51.200 - 51.400", "FM", "Wejścia przemienników"),
            BandSegment("51.400 - 52.000", "FM", "FM simplex 51.410–51.590; wywołanie 51.510; wyjścia przemienników 51.810–51.990"),
            BandSegment("52.000 - 54.000", "Wszystkie", "Wg krajowych przydziałów")
        )),
        Band("2m", "144 - 146 MHz", listOf(
            BandSegment("144.000 - 144.025", "Sat", "Satelity"),
            BandSegment("144.025 - 144.100", "CW", "Tylko CW; wywołanie 144.050"),
            BandSegment("144.100 - 144.150", "CW, Digi", "Telegrafia i MGM; EME 144.110–144.160"),
            BandSegment("144.150 - 144.400", "Wszystkie", "SSB, CW, MGM (wywołanie SSB: 144.300, FT8: 144.174)"),
            BandSegment("144.400 - 144.490", "Beacony", "Beacony, wyłącznie"),
            BandSegment("144.500 - 144.794", "Wszystkie", "SSTV 144.500; emisje cyfrowe 144.600; ATV 144.750"),
            BandSegment("144.794 - 144.9625", "Digi", "APRS 144.800; bramki internetowe DV 144.8125–144.8625"),
            BandSegment("144.975 - 145.194", "FM", "Wejścia przemienników, wyłącznie"),
            BandSegment("145.194 - 145.206", "FM", "ISS 145.200"),
            BandSegment("145.206 - 145.5625", "FM", "FM/DV simplex; DV 145.375; wywołanie 145.500; bramki internetowe 145.2375–145.3375"),
            BandSegment("145.575 - 145.7935", "FM", "Wyjścia przemienników, wyłącznie"),
            BandSegment("145.794 - 145.806", "FM", "ISS 145.800"),
            BandSegment("145.806 - 146.000", "Sat", "Satelity, wyłącznie")
        )),
        Band("70cm", "430 - 440 MHz", listOf(
            BandSegment("430.000 - 431.975", "Wszystkie", "Wg planów krajowych: przemienniki i łącza cyfrowe; wejścia przemienników 431.050–431.975 (7.6 MHz)"),
            BandSegment("432.000 - 432.400", "Wszystkie", "Wywołanie CW 432.050, SSB 432.200; FT8 432.174; MS 432.370"),
            BandSegment("432.400 - 432.490", "Beacony", "Beacony, wyłącznie"),
            BandSegment("432.500 - 432.975", "Wszystkie", "APRS 432.500; wejścia przemienników 432.600–432.975 (2 MHz)"),
            BandSegment("433.000 - 433.375", "FM", "Wejścia przemienników (1.6 MHz)"),
            BandSegment("433.400 - 433.5875", "FM", "FM/DV simplex; SSTV 433.400; DV 433.450; wywołanie 433.500"),
            BandSegment("433.600 - 434.000", "Wszystkie", "Emisje cyfrowe; eksperymenty 434.000 (LoRa)"),
            BandSegment("434.000 - 434.5875", "Wszystkie", "ATV; emisje cyfrowe 434.450–434.575"),
            BandSegment("434.600 - 434.9875", "FM", "Wyjścia przemienników (1.6 / 2 MHz)"),
            BandSegment("435.000 - 436.000", "Sat", "Satelity"),
            BandSegment("436.000 - 438.000", "Sat", "Satelity i DATV"),
            BandSegment("438.000 - 440.000", "Wszystkie", "Emisje cyfrowe; wyjścia przemienników 438.650–439.5875 (7.6 MHz)")
        )),
        Band("23cm", "1240 - 1300 MHz", listOf(
            BandSegment("1240.000 - 1241.000", "Wszystkie", "Emisje cyfrowe"),
            BandSegment("1241.000 - 1243.250", "Wszystkie", "Wyjścia przemienników 1242.025–1242.700; emisje cyfrowe"),
            BandSegment("1243.250 - 1260.000", "ATV", "ATV, DATV"),
            BandSegment("1260.000 - 1270.000", "Sat", "Uplink satelitów"),
            BandSegment("1270.000 - 1272.000", "Wszystkie", "Wejścia przemienników 1270.025–1270.700; emisje cyfrowe"),
            BandSegment("1272.000 - 1290.994", "ATV", "ATV, DATV"),
            BandSegment("1290.994 - 1291.481", "FM", "Wejścia przemienników (RM0–RM19)"),
            BandSegment("1291.494 - 1296.000", "Wszystkie", "Wszystkie emisje"),
            BandSegment("1296.000 - 1296.150", "CW, Digi", "Telegrafia i MGM; EME 1296.000–1296.025"),
            BandSegment("1296.150 - 1296.800", "Wszystkie", "SSB, CW, MGM (wywołanie: 1296.200); obraz 1296.500"),
            BandSegment("1296.800 - 1296.994", "Beacony", "Beacony, wyłącznie"),
            BandSegment("1296.994 - 1297.481", "FM", "Wyjścia przemienników (RM0–RM19)"),
            BandSegment("1297.494 - 1297.981", "FM", "FM simplex; wywołanie 1297.500; DV 1297.725"),
            BandSegment("1298.000 - 1300.000", "Wszystkie", "Wszystkie emisje, emisje cyfrowe")
        ))
    )
}
