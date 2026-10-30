package io.github.sp3wro.hamradiotoolkit.data.model

data class SatFrequency(
    val mode: String,
    val uplink: String,
    val downlink: String,
    val tone: String
)

val satelliteDatabase = mapOf(
    "ISS" to listOf(
        SatFrequency("FM Voice / SSTV", "145.990", "145.800", "67.0 Hz"),
        SatFrequency("APRS Digipeater", "145.825", "145.825", "—")
    ),
    "ARISS" to listOf(
        SatFrequency("FM Voice / SSTV", "145.990", "145.800", "67.0 Hz"),
        SatFrequency("APRS Digipeater", "145.825", "145.825", "—")
    ),
    "SO-50" to listOf(
        SatFrequency("FM Voice", "145.850", "436.795", "67.0 Hz")
    ),
    "AO-91" to listOf(
        SatFrequency("FM Voice", "435.250", "145.960", "67.0 Hz")
    ),
    "AO-92" to listOf(
        SatFrequency("FM Voice", "435.350", "145.880", "67.0 Hz")
    ),
    "AO-7" to listOf(
        SatFrequency("CW/SSB (Mode A)", "145.850 - 145.950", "29.400 - 29.500", "—"),
        SatFrequency("CW/SSB (Mode B)", "432.125 - 432.175", "145.975 - 145.925", "—")
    ),
    "AO-27" to listOf(
        SatFrequency("FM Voice", "145.850", "436.795", "—")
    ),
    "FO-29" to listOf(
        SatFrequency("CW/SSB", "145.900 - 145.950", "435.800 - 435.900", "—")
    ),
    "XW-2A" to listOf(
        SatFrequency("CW/SSB", "435.030 - 435.050", "145.660 - 145.680", "—")
    ),
    "XW-2B" to listOf(
        SatFrequency("CW/SSB", "435.090 - 435.110", "145.730 - 145.750", "—")
    ),
    "XW-2C" to listOf(
        SatFrequency("CW/SSB", "435.150 - 435.170", "145.790 - 145.810", "—")
    ),
    "XW-2D" to listOf(
        SatFrequency("CW/SSB", "435.210 - 435.230", "145.855 - 145.875", "—")
    ),
    "XW-2F" to listOf(
        SatFrequency("CW/SSB", "435.330 - 435.350", "145.975 - 145.995", "—")
    ),
    "HO-107" to listOf(
        SatFrequency("FM Voice / APRS", "145.920", "435.840", "104.2 Hz")
    ),
    "RS-44" to listOf(
        SatFrequency("CW / SSB Inverting", "145.965 +/- 30 kHz", "435.640 +/- 30 kHz", "— (Linear)")
    ),
    "IO-86" to listOf(
        SatFrequency("FM Voice", "145.880", "435.880", "88.5 Hz"),
        SatFrequency("APRS", "145.825", "145.825", "—")
    ),
    "PO-101" to listOf(
        SatFrequency("FM Voice", "437.500", "145.900", "141.3 Hz"),
        SatFrequency("APRS", "145.825", "145.825", "—")
    ),
    "CAS-4A" to listOf(
        SatFrequency("CW / SSB", "435.220", "145.870", "— (Linear)")
    ),
    "CAS-4B" to listOf(
        SatFrequency("CW / SSB", "435.280", "145.925", "— (Linear)")
    ),
    "CAS-6" to listOf(
        SatFrequency("CW / SSB", "435.280", "145.925", "— (Linear)")
    ),
    "NO-84" to listOf(
        SatFrequency("APRS", "145.825", "145.825", "—"),
        SatFrequency("FM PSK", "435.350", "435.350", "—")
    ),
    "NO-104" to listOf(
        SatFrequency("APRS", "145.825", "145.825", "—")
    ),
    "EO-88" to listOf(
        SatFrequency("CW / SSB", "145.990", "435.045", "— (Linear)")
    ),
    "SO-114" to listOf(
        SatFrequency("FM Voice", "145.970", "436.400", "—")
    ),
    "TEVEL-1" to listOf(
        SatFrequency("FM Voice", "145.970", "436.400", "—")
    ),
    "TEVEL-2" to listOf(
        SatFrequency("FM Voice", "145.970", "436.400", "—")
    ),
    "TEVEL-3" to listOf(
        SatFrequency("FM Voice", "145.970", "436.400", "—")
    ),
    "TEVEL-4" to listOf(
        SatFrequency("FM Voice", "145.970", "436.400", "—")
    ),
    "TEVEL-5" to listOf(
        SatFrequency("FM Voice", "145.970", "436.400", "—")
    ),
    "TEVEL-6" to listOf(
        SatFrequency("FM Voice", "145.970", "436.400", "—")
    ),
    "TEVEL-7" to listOf(
        SatFrequency("FM Voice", "145.970", "436.400", "—")
    ),
    "TEVEL-8" to listOf(
        SatFrequency("FM Voice", "145.970", "436.400", "—")
    ),
    "GREENCUBE" to listOf(
        SatFrequency("Digipeater", "435.310", "435.310", "—")
    ),
    "IO-117" to listOf(
        SatFrequency("Digipeater", "435.310", "435.310", "—")
    ),
    "LILACSAT-2" to listOf(
        SatFrequency("FM Voice", "145.990", "437.200", "—")
    ),
    "MARINA" to listOf(
        SatFrequency("GFSK 9k6", "145.925", "145.925", "—")
    )
)
