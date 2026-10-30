package io.github.sp3wro.hamradiotoolkit.dsstv

/** Stan odbieranego pliku (obiektu MOT). */
data class DrmFileProgress(
    val transportId: Int,
    val name: String?,
    val receivedSegments: Int,
    /** Liczba segmentów treści albo null, dopóki nie przyszedł ostatni. */
    val totalSegments: Int?,
    val bodySize: Int?
)

/** Odebrany plik: obrazek (jp2, jpg, avif, png…) albo inny. */
class DrmFile(
    val name: String,
    val extension: String,
    val bytes: ByteArray,
    /** Ile symboli poprawił kod RS (gdy plik był chroniony RS1–RS4). */
    val rsCorrected: Int? = null
)

/*
 * Warstwa danych DRM: tryb pakietowy (ES 201 980 6.6.4), grupy danych MSC i protokół MOT
 * (EN 300 401 / TS 101 756 – nagłówek obiektu z BodySize, ContentType i ContentName, segmenty treści).
 */
class DrmDataDecoder(
    private val onProgress: (DrmFileProgress) -> Unit = {},
    private val onFile: (DrmFile) -> Unit = {},
    /** Początek treści (ciągłe segmenty od zera) – do podglądu obrazka w trakcie odbioru. */
    private val onPartial: (name: String, prefix: ByteArray) -> Unit = { _, _ -> }
) {
    private val unit = java.io.ByteArrayOutputStream()
    private var unitActive = false
    private var lastCi = -1

    private class MotObject(val transportId: Int) {
        val headerSegments = HashMap<Int, ByteArray>()
        var headerLast = -1
        val bodySegments = HashMap<Int, ByteArray>()
        var bodyLast = -1
        var segmentSize = 0
        var name: String? = null
        var bodySize: Int? = null
        var delivered = false
        var lastUpdate = 0L
        var lastPreviewSegments = 0
    }

    private val objects = LinkedHashMap<Int, MotObject>()
    private var counter = 0L

    /** Bajty ramki MSC (pakiet: nagłówek 1 B, dane, CRC-16). Zwraca, czy CRC pakietu było poprawne. */
    fun pushPacket(packet: IntArray): Boolean {
        if (packet.size < 4) return false
        val crc = DrmCrc.compute(packet, 0, packet.size - 2, 16)
        if (crc != ((packet[packet.size - 2] shl 8) or packet[packet.size - 1])) {
            unitActive = false
            return false
        }
        val h = packet[0]
        val first = h and 0x80 != 0
        val last = h and 0x40 != 0
        val padded = h and 0x08 != 0
        val ci = h and 0x07
        var from = 1
        var to = packet.size - 2
        if (padded) {
            val useful = packet[1]
            from = 2
            to = minOf(to, 2 + useful)
        }
        if (first) {
            unit.reset()
            unitActive = true
        } else if (!unitActive || ci != (lastCi + 1) % 8) {
            // Zgubiony pakiet w środku jednostki danych
            unitActive = false
            lastCi = ci
            return true
        }
        lastCi = ci
        for (i in from until to) unit.write(packet[i])
        if (last && unitActive) {
            unitActive = false
            pushDataGroup(unit.toByteArray().map { it.toInt() and 0xFF }.toIntArray())
        }
        return true
    }

    /** Grupa danych MSC z segmentem MOT. */
    internal fun pushDataGroup(g: IntArray) {
        if (g.size < 4) return
        val ext = g[0] and 0x80 != 0
        val hasCrc = g[0] and 0x40 != 0
        val hasSegment = g[0] and 0x20 != 0
        val hasUserAccess = g[0] and 0x10 != 0
        val type = g[0] and 0x0F
        val end = if (hasCrc) g.size - 2 else g.size
        if (hasCrc) {
            val crc = DrmCrc.compute(g, 0, g.size - 2, 16)
            if (crc != ((g[g.size - 2] shl 8) or g[g.size - 1])) return
        }
        var p = 2 + if (ext) 2 else 0
        var lastSeg = false
        var segNum = 0
        if (hasSegment) {
            if (p + 2 > end) return
            lastSeg = g[p] and 0x80 != 0
            segNum = ((g[p] and 0x7F) shl 8) or g[p + 1]
            p += 2
        }
        var tid = -1
        if (hasUserAccess) {
            if (p >= end) return
            val tidFlag = g[p] and 0x10 != 0
            val len = g[p] and 0x0F
            if (tidFlag && p + 3 <= end) tid = (g[p + 1] shl 8) or g[p + 2]
            p += 1 + len
        }
        if (tid < 0 || p + 2 > end) return
        // Nagłówek segmentu: powtórzenia (3 b), rozmiar segmentu (13 b)
        val segSize = ((g[p] and 0x1F) shl 8) or g[p + 1]
        p += 2
        if (p + segSize > end) return
        val data = ByteArray(segSize) { g[p + it].toByte() }
        val obj = objects.getOrPut(tid) { MotObject(tid) }
        obj.lastUpdate = ++counter
        when (type) {
            3 -> {
                obj.headerSegments[segNum] = data
                if (lastSeg) obj.headerLast = segNum
                parseHeader(obj)
            }
            4 -> {
                obj.bodySegments[segNum] = data
                if (lastSeg) obj.bodyLast = segNum
                // Wszystkie segmenty poza ostatnim mają ten sam (największy) rozmiar
                obj.segmentSize = maxOf(obj.segmentSize, segSize)
            }
            else -> return
        }
        // Najwyżej kilka obiektów naraz
        while (objects.size > 4) objects.remove(objects.minByOrNull { it.value.lastUpdate }!!.key)
        onProgress(
            DrmFileProgress(
                tid, obj.name, obj.bodySegments.size,
                if (obj.bodyLast >= 0) obj.bodyLast + 1 else null, obj.bodySize
            )
        )
        tryDeliver(obj)
        preview(obj)
    }

    private fun preview(obj: MotObject) {
        if (obj.delivered) return
        val name = obj.name ?: return
        if (RsFileCodec.isRsExtension(name.substringAfterLast('.', ""))) return
        var contiguous = 0
        while (contiguous in obj.bodySegments) contiguous++
        // Podgląd co ok. 10% treści
        val total = if (obj.bodyLast >= 0) obj.bodyLast + 1 else contiguous + 10
        if (contiguous - obj.lastPreviewSegments < maxOf(2, total / 10)) return
        obj.lastPreviewSegments = contiguous
        val out = java.io.ByteArrayOutputStream()
        for (i in 0 until contiguous) out.write(obj.bodySegments.getValue(i))
        onPartial(name, out.toByteArray())
    }

    private fun parseHeader(obj: MotObject) {
        if (obj.headerLast < 0 || (0..obj.headerLast).any { it !in obj.headerSegments }) return
        val h = (0..obj.headerLast).flatMap { obj.headerSegments.getValue(it).toList() }.map { it.toInt() and 0xFF }
        if (h.size < 7) return
        obj.bodySize = (h[0] shl 20) or (h[1] shl 12) or (h[2] shl 4) or (h[3] shr 4)
        val headerSize = ((h[3] and 0x0F) shl 9) or (h[4] shl 1) or (h[5] shr 7)
        var p = 7
        val end = minOf(headerSize, h.size)
        while (p < end) {
            val pli = h[p] shr 6
            val paramId = h[p] and 0x3F
            p++
            val len = when (pli) {
                0 -> 0
                1 -> 1
                2 -> 4
                else -> {
                    if (p >= end) return
                    val extFlag = h[p] and 0x80 != 0
                    val l = if (extFlag && p + 1 < end) ((h[p] and 0x7F) shl 8) or h[p + 1] else h[p] and 0x7F
                    p += if (extFlag) 2 else 1
                    l
                }
            }
            if (p + len > end) return
            if (paramId == 12 && len >= 1) {
                // ContentName: bajt zestawu znaków, potem nazwa
                obj.name = String(ByteArray(len - 1) { h[p + 1 + it].toByte() }, Charsets.ISO_8859_1).trim()
            }
            p += len
        }
        tryDeliver(obj)
    }

    private fun tryDeliver(obj: MotObject) {
        if (obj.delivered) return
        val name = obj.name ?: return
        val last = obj.bodyLast
        if (last < 0) return
        val ext = name.substringAfterLast('.', "").lowercase()
        val complete = (0..last).all { it in obj.bodySegments }
        if (complete) {
            val body = java.io.ByteArrayOutputStream()
            for (i in 0..last) body.write(obj.bodySegments.getValue(i))
            val bytes = body.toByteArray()
            deliver(obj, name, ext, bytes, null)
            return
        }
        // Plik RS: można odtworzyć brakujące segmenty, gdy ich nie jest za dużo
        if (RsFileCodec.isRsExtension(ext) && obj.segmentSize > 0) {
            val segSize = obj.segmentSize
            val lastSize = obj.bodySegments[last]?.size ?: segSize
            val total = last * segSize + lastSize
            val buf = ByteArray(total)
            val missing = BooleanArray(total)
            for (i in 0..last) {
                val seg = obj.bodySegments[i]
                val start = i * segSize
                val len = if (i == last) lastSize else segSize
                if (seg == null) {
                    for (j in start until minOf(start + len, total)) missing[j] = true
                } else System.arraycopy(seg, 0, buf, start, minOf(seg.size, total - start))
            }
            val missingCount = missing.count { it }
            val k = when (ext) { "rs1" -> 224; "rs2" -> 192; "rs3" -> 160; else -> 128 }
            // Każdy blok RS dostaje proporcjonalną część braków – bez szans przy więcej niż (255−k)/255
            if (missingCount.toDouble() / total > (255.0 - k) / 255.0) return
            deliver(obj, name, ext, buf, missing)
        }
    }

    private fun deliver(obj: MotObject, name: String, ext: String, bytes: ByteArray, missing: BooleanArray?) {
        if (RsFileCodec.isRsExtension(ext)) {
            val res = RsFileCodec.decode(bytes, ext, missing) ?: return
            obj.delivered = true
            val base = name.substringBeforeLast('.')
            onFile(DrmFile("$base.${res.extension}", res.extension, res.bytes, res.corrected))
        } else {
            obj.delivered = true
            onFile(DrmFile(name, ext, bytes))
        }
    }
}
