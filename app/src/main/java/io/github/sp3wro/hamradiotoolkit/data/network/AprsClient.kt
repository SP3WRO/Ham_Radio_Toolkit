package io.github.sp3wro.hamradiotoolkit.data.network

import android.util.Log
import io.github.sp3wro.hamradiotoolkit.BuildConfig
import io.github.sp3wro.hamradiotoolkit.data.model.AprsPacket
import io.github.sp3wro.hamradiotoolkit.utils.Callsigns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import kotlin.coroutines.coroutineContext

private const val SERVER = "euro.aprs2.net"
private const val PORT = 14580

class AprsClient(
    private val callsign: String,
    private val latitude: Double,
    private val longitude: Double,
    private val radiusKm: Int
) {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null

    /** Znak do nadawania albo null – bez poprawnego znaku krótkofalarskiego połączenie jest tylko do odczytu. */
    private val transmitCall: String? = Callsigns.aprsTransmitCall(callsign)
    val canTransmit: Boolean get() = transmitCall != null

    /** Strumień pakietów pozycji; [onConnected] jest wołane po zalogowaniu do serwera. */
    fun connectAndListen(onConnected: () -> Unit = {}): Flow<AprsPacket> = flow {
        try {
            // Bez poprawnego znaku logujemy się tylko do odczytu (pass -1) – serwer i tak odrzuci wtedy każdą ramkę
            val loginCall = transmitCall ?: "N0CALL"
            val pass = if (transmitCall != null) AprsParser.passcode(transmitCall) else -1

            val s = Socket()
            socket = s
            s.connect(InetSocketAddress(SERVER, PORT), 15_000)
            // Serwer wysyła komunikat podtrzymujący co ~20 s – 90 s ciszy oznacza martwe połączenie
            s.soTimeout = 90_000
            writer = PrintWriter(s.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))

            // Logowanie do serwera (z filtrem na promień: r/lat/lon/dist)
            val filter = String.format(Locale.US, "r/%.4f/%.4f/%d", latitude, longitude, radiusKm)
            writer?.print("user $loginCall pass $pass vers HamRadioToolkit ${BuildConfig.VERSION_NAME} filter $filter\r\n")
            writer?.flush()

            // Pętla odczytu
            while (coroutineContext.isActive && socket?.isClosed == false) {
                val line = reader.readLine() ?: break
                if (line.startsWith("#")) {
                    if (line.startsWith("# logresp")) onConnected()
                    continue // Pozostałe komentarze serwera ignorujemy
                }

                val packet = AprsParser.parse(line)
                if (packet != null) {
                    emit(packet)
                }
            }
        } catch (e: IOException) {
            Log.w("AprsClient", "Połączenie z APRS-IS przerwane", e)
        } finally {
            close()
        }
    }.flowOn(Dispatchers.IO)

    val isOpen: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /** Wysyła pozycję; bez poprawnego znaku nic nie robi i zwraca false. */
    suspend fun sendBeacon(ssid: String, icon: String, comment: String, lat: Double, lon: Double): Boolean {
        val base = transmitCall ?: return false
        return withContext(Dispatchers.IO) {
            if (writer != null) {
                val call = if (ssid.isNotEmpty() && ssid != "0" && ssid != "-0") "$base$ssid" else base
                val latStr = formatLat(lat)
                val lonStr = formatLon(lon)
                
                val table = if (icon.isNotEmpty()) icon[0] else '/'
                val symbol = if (icon.length > 1) icon[1] else '>'
                
                val beacon = "$call>APRS,TCPIP*:=$latStr$table$lonStr$symbol$comment"
                writer?.print("$beacon\r\n")
                writer?.flush()
                true
            } else {
                false
            }
        }
    }

    fun close() {
        try {
            writer?.close()
            socket?.close()
        } catch (e: Exception) {}
    }

    internal companion object {
        // Zaokrąglenie do setnych minuty przed podziałem na stopnie – inaczej 59,996' dawało "60.00"
        private fun degMin(value: Double): Pair<Int, Double> {
            val hundredths = Math.round(Math.abs(value) * 6000.0)
            return (hundredths / 6000).toInt() to (hundredths % 6000) / 100.0
        }

        fun formatLat(lat: Double): String {
            val (deg, min) = degMin(lat)
            return String.format(Locale.US, "%02d%05.2f%c", deg, min, if (lat >= 0) 'N' else 'S')
        }

        fun formatLon(lon: Double): String {
            val (deg, min) = degMin(lon)
            return String.format(Locale.US, "%03d%05.2f%c", deg, min, if (lon >= 0) 'E' else 'W')
        }
    }
}
