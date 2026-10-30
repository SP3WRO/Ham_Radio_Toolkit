package io.github.sp3wro.hamradiotoolkit.dsstv

import io.github.sp3wro.hamradiotoolkit.dsp.MixedRadixFft
import io.github.sp3wro.hamradiotoolkit.dsp.lowpassTaps
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Stan synchronizacji odbiornika. */
data class DrmSyncStatus(
    val locked: Boolean,
    val mode: DrmRobustness? = null,
    val occupancy: DrmOccupancy? = null,
    val frequencyOffsetHz: Double = 0.0,
    val snrDb: Double? = null,
    val facOkPercent: Int = 0,
    val fac: DrmFacInfo? = null
)

/**
 * Odbiornik HamDRM: audio 12 kHz → synchronizacja OFDM → komórki FAC/MSC → bajty ramek MSC.
 * [onFrame] dostaje bajty każdej ramki logicznej MSC (pakiet z nagłówkiem i CRC) albo null, gdy FAC był zły.
 */
class DrmReceiver(
    private val sampleRate: Int = 12000,
    private val onStatus: (DrmSyncStatus) -> Unit = {},
    private val onFrame: (bytes: IntArray, fac: DrmFacInfo) -> Unit = { _, _ -> }
) {
    private val decim = 48000 / sampleRate

    // ---------------------------------------------------------------- bufory próbek
    private var raw = FloatArray(1 shl 18)
    private var rawStart = 0L           // indeks bezwzględny raw[0]
    private var rawEnd = 0L             // indeks bezwzględny za ostatnią próbką

    // Sygnał po przemianie (x) i po filtrze (z) – ten sam indeks co raw
    private var xRe = FloatArray(raw.size)
    private var xIm = FloatArray(raw.size)
    private var xEnd = 0L
    private var zRe = FloatArray(raw.size)
    private var zIm = FloatArray(raw.size)
    private var zEnd = 0L               // do kiedy z jest policzone
    private var ncoPhase = 0.0
    private var mixHz = ACQ_MIX_HZ
    private val taps = lowpassTaps(1700.0 / sampleRate, 97)
    private val firDelay = taps.size / 2

    // ---------------------------------------------------------------- stan synchronizacji
    private var locked = false
    private var mode = DrmRobustness.B
    private var occupancy = DrmOccupancy.SO_0
    private var map = DrmCellMap.of(mode, occupancy)
    private var n = 0                   // rozmiar FFT
    private var g = 0                   // prefiks
    private var ts = 0                  // długość symbolu
    private var centerBin = 0           // nośna c leży w binie 0
    private var nextGuardStart = 0L     // początek prefiksu następnego symbolu (bez dryfu)
    private var drift = 0               // przesunięcie okna FFT względem siatki (próbki)
    private var tauSmooth = 0.0
    private var frameSymbol = 0         // numer w ramce następnego symbolu
    private var fft: MixedRadixFft? = null
    private var deltaHz = 0.0           // odchyłka częstotliwości względem nominału

    // Historia symboli do interpolacji kanału w czasie
    private class Symbol(val frameSym: Int, val re: FloatArray, val im: FloatArray)
    private val history = ArrayList<Symbol>()

    // Zbieranie ramki
    private var facRe = FloatArray(0); private var facIm = FloatArray(0); private var facW = FloatArray(0)
    private var mscRe = FloatArray(0); private var mscIm = FloatArray(0); private var mscW = FloatArray(0)
    private var facCount = 0
    private var mscCount = 0
    private var frameStarted = false
    private var badFacInRow = 0
    private var facTotal = 0
    private var facOk = 0
    private var lastFac: DrmFacInfo? = null
    private var snrDb: Double? = null
    private var noiseAcc = 0.0
    private var noiseCount = 0

    // Przeplot komórek MSC (głęboki: 5 ramek)
    private val interleaveHistory = ArrayList<DrmCells>()

    private var lastAcquireAttempt = NEVER

    fun reset() {
        rawStart = 0; rawEnd = 0; zEnd = 0; xEnd = 0
        locked = false
        history.clear()
        interleaveHistory.clear()
        lastAcquireAttempt = NEVER
        facTotal = 0; facOk = 0; lastFac = null
        onStatus(DrmSyncStatus(false))
    }

    /** Kolejne próbki audio (−1..1) w częstotliwości [sampleRate]. */
    fun process(samples: FloatArray, count: Int = samples.size) {
        append(samples, count)
        if (!locked) {
            val window = ACQ_SECONDS * sampleRate
            // Próba synchronizacji co 0,5 s na ostatnich [ACQ_SECONDS] sekundach
            if (rawEnd - rawStart >= window && rawEnd - lastAcquireAttempt >= sampleRate / 2) {
                lastAcquireAttempt = rawEnd
                acquire(rawEnd - window)
            }
        }
        if (locked) runSymbols()
    }

    // ---------------------------------------------------------------- bufor
    private fun append(samples: FloatArray, count: Int) {
        val needed = (rawEnd - rawStart + count).toInt()
        if (needed > raw.size) {
            // Zostawiamy ostatnie MAX_KEEP s (pliki dekodujemy na bieżąco, więc starsze nie są potrzebne)
            val keep = minOf((rawEnd - rawStart).toInt(), MAX_KEEP_SECONDS * sampleRate)
            val drop = (rawEnd - rawStart).toInt() - keep
            if (needed - drop > raw.size) {
                val size = Integer.highestOneBit(needed - drop) shl 1
                raw = raw.copyOf(size)
                xRe = xRe.copyOf(size); xIm = xIm.copyOf(size)
                zRe = zRe.copyOf(size); zIm = zIm.copyOf(size)
            }
            if (drop > 0) {
                for (a in arrayOf(raw, xRe, xIm, zRe, zIm)) System.arraycopy(a, drop, a, 0, keep)
                rawStart += drop
            }
        }
        System.arraycopy(samples, 0, raw, (rawEnd - rawStart).toInt(), count)
        rawEnd += count
    }

    private fun idx(abs: Long) = (abs - rawStart).toInt()

    /** Ustawia przemianę od próbki [from] (z liczone od [from], x – z zapasem na długość filtra). */
    private fun restartMixer(from: Long, hz: Double) {
        mixHz = hz
        ncoPhase = 0.0
        xEnd = maxOf(rawStart, from - taps.size)
        zEnd = from
    }

    /** Przemiana częstotliwości (NCO z ciągłą fazą) i filtr dolnoprzepustowy – z[n] do [upTo]. */
    private fun computeZ(upTo: Long) {
        val end = minOf(upTo, rawEnd)
        if (xEnd < rawStart) xEnd = rawStart
        if (zEnd < rawStart) zEnd = rawStart
        // x[m] = r[m]·e^{−jφ(m)} – faza ciągła także przy zmianach częstotliwości (AFC)
        val w = 2 * PI * mixHz / sampleRate
        while (xEnd < end) {
            val i = idx(xEnd)
            xRe[i] = (raw[i] * cos(ncoPhase)).toFloat()
            xIm[i] = (-raw[i] * sin(ncoPhase)).toFloat()
            ncoPhase += w
            if (ncoPhase > 2 * PI) ncoPhase -= 2 * PI
            xEnd++
        }
        while (zEnd < end) {
            val base = idx(zEnd)
            var re = 0f
            var im = 0f
            val tMax = minOf(taps.size - 1, base)
            for (t in 0..tMax) {
                re += taps[t] * xRe[base - t]
                im += taps[t] * xIm[base - t]
            }
            zRe[base] = re
            zIm[base] = im
            zEnd++
        }
    }

    // ---------------------------------------------------------------- akwizycja
    private fun acquire(start: Long) {
        // 1) z z próbną przemianą ACQ_MIX_HZ
        restartMixer(start, ACQ_MIX_HZ)
        computeZ(rawEnd)
        val len = (rawEnd - start).toInt()
        val off = idx(start) + taps.size

        var best: DrmRobustness? = null
        var bestMetric = 0.0
        var bestT = 0
        var bestAngle = 0.0
        for (m in DrmRobustness.values()) {
            val nn = m.fftSize48 / decim
            val gg = m.guard48 / decim
            val tt = nn + gg
            val foldRe = DoubleArray(tt)
            val foldIm = DoubleArray(tt)
            val foldE = DoubleArray(tt)
            val usable = len - taps.size - nn - gg
            if (usable < tt * 8) continue
            // Suma przesuwna iloczynu z[n]·z*[n+N] po długości prefiksu
            var sRe = 0.0; var sIm = 0.0; var sE = 0.0
            for (i in 0 until gg) {
                val a = off + i
                sRe += zRe[a] * zRe[a + nn] + zIm[a] * zIm[a + nn]
                sIm += zIm[a] * zRe[a + nn] - zRe[a] * zIm[a + nn]
                sE += 0.5 * (zRe[a] * zRe[a] + zIm[a] * zIm[a] + zRe[a + nn] * zRe[a + nn] + zIm[a + nn] * zIm[a + nn])
            }
            for (i in 0 until usable) {
                val k = i % tt
                foldRe[k] += sRe; foldIm[k] += sIm; foldE[k] += sE
                val a = off + i
                val b = a + gg
                sRe += (zRe[b] * zRe[b + nn] + zIm[b] * zIm[b + nn]) - (zRe[a] * zRe[a + nn] + zIm[a] * zIm[a + nn])
                sIm += (zIm[b] * zRe[b + nn] - zRe[b] * zIm[b + nn]) - (zIm[a] * zRe[a + nn] - zRe[a] * zIm[a + nn])
                sE += 0.5 * (zRe[b] * zRe[b] + zIm[b] * zIm[b] + zRe[b + nn] * zRe[b + nn] + zIm[b + nn] * zIm[b + nn]) -
                    0.5 * (zRe[a] * zRe[a] + zIm[a] * zIm[a] + zRe[a + nn] * zRe[a + nn] + zIm[a + nn] * zIm[a + nn])
            }
            for (k in 0 until tt) {
                val metric = hypot(foldRe[k], foldIm[k]) / (foldE[k] + 1e-12)
                if (metric > bestMetric) {
                    bestMetric = metric
                    best = m
                    bestT = k
                    bestAngle = atan2(foldIm[k], foldRe[k])
                }
            }
        }
        debugLog?.invoke("acq start=$start len=$len best=$best metric=$bestMetric t=$bestT angle=$bestAngle")
        val m = best ?: return
        if (bestMetric < ACQ_MIN_METRIC) {
            onStatus(DrmSyncStatus(false))
            return
        }

        val nn = m.fftSize48 / decim
        val gg = m.guard48 / decim
        val tt = nn + gg
        val df = sampleRate.toDouble() / nn
        // 2) Ułamkowa odchyłka częstotliwości z kąta korelacji prefiksu
        var deltaFrac = ACQ_MIX_HZ - DrmTables.DC_FREQ_HZ - bestAngle / (2 * PI) * df
        deltaFrac -= df * Math.round(deltaFrac / df)
        // Nośna c trafia do binu 0. c·G/N musi być całkowite – inaczej faza symboli skacze co symbol
        // (np. w trybie B o 180°) i interpolacja kanału w czasie się psuje
        val c = m.guardDen * Math.round(1225.0 / df / m.guardDen).toInt()

        // 3) Symbole z poprawioną przemianą
        restartMixer(start, DrmTables.DC_FREQ_HZ + deltaFrac + c * df)
        computeZ(rawEnd)
        val f = MixedRadixFft(nn)
        val backoff = gg / 4
        val firstGuard = off + bestT
        val symbols = ArrayList<Pair<DoubleArray, DoubleArray>>()
        var s0 = firstGuard
        while (s0 + tt <= len + idx(start)) {
            symbols += fftAt(f, s0 + gg - backoff, nn)
            s0 += tt
        }
        if (symbols.size < m.symbolsPerFrame + 4) return

        // 4) Całkowita odchyłka (wielokrotność odstępu nośnych) – piloty częstotliwości mają stałą fazę
        val fp = DrmTables.freqPilots(m)
        val maxShift = (250.0 / df).toInt() + 1
        var bestQ = 0
        var bestQMetric = -1.0
        for (q in -maxShift..maxShift) {
            var num = 0.0
            var den = 1e-12
            for (p in 0 until fp.size / 2) {
                val b = Math.floorMod(fp[p * 2] - c + q, nn)
                var aRe = 0.0; var aIm = 0.0
                for (j in 0 until symbols.size - 2) {
                    val (r1, i1) = symbols[j]
                    val (r2, i2) = symbols[j + 2]
                    aRe += r1[b] * r2[b] + i1[b] * i2[b]
                    aIm += i1[b] * r2[b] - r1[b] * i2[b]
                    den += r1[b] * r1[b] + i1[b] * i1[b]
                }
                num += hypot(aRe, aIm)
            }
            val metric = num / den
            if (metric > bestQMetric) {
                bestQMetric = metric
                bestQ = q
            }
        }

        // 5) Synchronizacja ramki: piloty czasu w symbolu 0 (różnicowo między sąsiednimi pilotami)
        val tp = DrmTables.timePilots(m)
        val score = DoubleArray(m.symbolsPerFrame)
        for (j in symbols.indices) {
            val (r, i) = symbols[j]
            var aRe = 0.0; var aIm = 0.0; var pw = 1e-12
            for (p in 0 until tp.size / 2 - 1) {
                val k1 = tp[p * 2]; val k2 = tp[p * 2 + 2]
                if (k2 - k1 > 2) continue
                val b1 = Math.floorMod(k1 - c + bestQ, nn)
                val b2 = Math.floorMod(k2 - c + bestQ, nn)
                // y1·conj(y2)·conj(p1·conj(p2))
                val yr = r[b1] * r[b2] + i[b1] * i[b2]
                val yi = i[b1] * r[b2] - r[b1] * i[b2]
                val dp = 2 * PI * (tp[p * 2 + 1] - tp[p * 2 + 3]) / 1024.0
                aRe += yr * cos(dp) + yi * sin(dp)
                aIm += yi * cos(dp) - yr * sin(dp)
                pw += hypot(r[b1], i[b1]) * hypot(r[b2], i[b2])
            }
            score[j % m.symbolsPerFrame] += aRe / pw
        }
        var phase = 0
        for (k in score.indices) if (score[k] > score[phase]) phase = k

        // 6) Zajętość pasma: moc na nośnych 2,5 kHz poza pasmem 2,3 kHz
        val k0max = DrmTables.kMax(m, DrmOccupancy.SO_0)
        val k1max = DrmTables.kMax(m, DrmOccupancy.SO_1)
        var inBand = 0.0; var extra = 0.0
        for ((r, i) in symbols) {
            for (k in DrmTables.kMin(m, DrmOccupancy.SO_0)..k0max) {
                val b = Math.floorMod(k - c + bestQ, nn); inBand += r[b] * r[b] + i[b] * i[b]
            }
            for (k in k0max + 1..k1max) {
                val b = Math.floorMod(k - c + bestQ, nn); extra += r[b] * r[b] + i[b] * i[b]
            }
        }
        val inBandAvg = inBand / (k0max - DrmTables.kMin(m, DrmOccupancy.SO_0) + 1)
        val extraAvg = extra / (k1max - k0max)
        val occ = if (extraAvg > 0.3 * inBandAvg) DrmOccupancy.SO_1 else DrmOccupancy.SO_0

        debugLog?.invoke(
            "lock: deltaFrac=$deltaFrac q=$bestQ (metric $bestQMetric) phase=$phase scores=${score.joinToString { "%.2f".format(it) }} " +
                "occ=$occ (extra/in=${extraAvg / inBandAvg})"
        )
        // Start śledzenia od pierwszego pełnego symbolu w oknie
        mode = m
        occupancy = occ
        map = DrmCellMap.of(m, occ)
        n = nn; g = gg; ts = tt
        centerBin = c
        fft = f
        deltaHz = deltaFrac + bestQ * df
        restartMixer(start, DrmTables.DC_FREQ_HZ + deltaHz + c * df)
        prevFp = null
        drift = 0
        tauSmooth = backoff.toDouble()
        nextGuardStart = start + taps.size + bestT
        frameSymbol = Math.floorMod(0 - phase, m.symbolsPerFrame)
        history.clear()
        interleaveHistory.clear()
        frameStarted = false
        badFacInRow = 0
        locked = true
        onStatus(DrmSyncStatus(true, mode, occupancy, deltaHz))
    }

    private fun fftAt(f: MixedRadixFft, startIdx: Int, nn: Int): Pair<DoubleArray, DoubleArray> {
        val inRe = DoubleArray(nn) { zRe[startIdx + it].toDouble() }
        val inIm = DoubleArray(nn) { zIm[startIdx + it].toDouble() }
        val outRe = DoubleArray(nn)
        val outIm = DoubleArray(nn)
        f.transform(inRe, inIm, outRe, outIm)
        return outRe to outIm
    }

    // ---------------------------------------------------------------- śledzenie, symbol po symbolu
    private fun runSymbols() {
        val f = fft ?: return
        val backoff = g / 4
        while (true) {
            val winStart = nextGuardStart + g - backoff + drift
            if (winStart + n + ts > rawEnd) break
            computeZ(winStart + n)
            if (winStart < rawStart + taps.size) {
                nextGuardStart += ts
                frameSymbol = (frameSymbol + 1) % mode.symbolsPerFrame
                continue
            }
            val (yr, yi) = fftAt(f, idx(winStart), n)
            val nc = map.numCarriers
            val re = FloatArray(nc)
            val im = FloatArray(nc)
            for (i in 0 until nc) {
                val k = map.kMin + i
                val b = Math.floorMod(k - centerBin, n)
                // Kompensacja przesunięć okna, żeby kanał był ciągły w czasie
                val a = -2 * PI * (k - centerBin) * drift / n
                val cr = cos(a); val ci = sin(a)
                re[i] = (yr[b] * cr - yi[b] * ci).toFloat()
                im[i] = (yr[b] * ci + yi[b] * cr).toFloat()
            }
            afc(re, im)
            history += Symbol(frameSymbol, re, im)
            if (history.size > 2 * mode.scatTimeInt + 1) history.removeAt(0)
            val delay = mode.scatTimeInt - 1
            if (history.size > delay) equalizeAndCollect(history.size - 1 - delay)

            nextGuardStart += ts
            frameSymbol = (frameSymbol + 1) % mode.symbolsPerFrame
            if (!locked) break
        }
    }

    private var prevFp: FloatArray? = null

    /** Korekta resztkowej częstotliwości: obrót fazy pilotów częstotliwości między symbolami. */
    private fun afc(re: FloatArray, im: FloatArray) {
        val fp = DrmTables.freqPilots(mode)
        val cur = FloatArray(fp.size)
        for (p in 0 until fp.size / 2) {
            val i = fp[p * 2] - map.kMin
            cur[p * 2] = re[i]; cur[p * 2 + 1] = im[i]
        }
        val prev = prevFp
        prevFp = cur
        if (prev == null) return
        var aRe = 0.0; var aIm = 0.0
        for (p in 0 until fp.size / 2) {
            // W trybie E dwa pierwsze piloty zmieniają znak co symbol
            val sign = if (mode == DrmRobustness.E && p != fp.size / 2 - 1) -1.0 else 1.0
            aRe += sign * (cur[p * 2] * prev[p * 2] + cur[p * 2 + 1] * prev[p * 2 + 1])
            aIm += sign * (cur[p * 2 + 1] * prev[p * 2] - cur[p * 2] * prev[p * 2 + 1])
        }
        val err = atan2(aIm, aRe) / (2 * PI) * sampleRate / ts
        if (abs(err) < sampleRate.toDouble() / n / 3) {
            val step = 0.1 * err
            mixHz += step
            deltaHz += step
        }
    }

    /** Estymacja kanału (piloty, interpolacja w czasie i częstotliwości), korekcja i zbieranie komórek. */
    private fun equalizeAndCollect(h: Int) {
        val sym = history[h]
        val s = sym.frameSym
        val nc = map.numCarriers
        val hRe = FloatArray(nc)
        val hIm = FloatArray(nc)
        val known = BooleanArray(nc)
        val x = mode.scatFreqInt
        val k0 = (x + 1) / 2
        for (i in 0 until nc) {
            val t = map.type[s][i]
            if (CellType.isPilot(t)) {
                setH(hRe, hIm, i, sym, s)
                known[i] = true
                continue
            }
            val k = map.kMin + i
            if (Math.floorMod(k - k0, x) != 0) continue
            // Nośna z pilotami rozproszonymi: najbliższe wystąpienia przed i po
            var before = -1; var after = -1
            for (d in 1 until history.size) {
                if (before < 0 && h - d >= 0 && map.type[history[h - d].frameSym][i] and CellType.SCAT != 0) before = h - d
                if (after < 0 && h + d < history.size && map.type[history[h + d].frameSym][i] and CellType.SCAT != 0) after = h + d
            }
            if (before < 0 && after < 0) continue
            val (bRe, bIm) = if (before >= 0) pilotH(history[before], i) else pilotH(history[after], i)
            val (aRe, aIm) = if (after >= 0) pilotH(history[after], i) else bRe to bIm
            val w = if (before >= 0 && after >= 0) (h - before).toFloat() / (after - before) else 0f
            hRe[i] = bRe + (aRe - bRe) * w
            hIm[i] = bIm + (aIm - bIm) * w
            known[i] = true
        }
        // Opóźnienie (dryf zegara) daje nachylenie fazy po nośnych – prostujemy je przed interpolacją
        // po częstotliwości, inaczej przy dużym opóźnieniu interpolacja między pilotami się psuje
        val tau0 = tauSmooth
        rotateByDelay(hRe, hIm, known, tau0)
        // Interpolacja liniowa po częstotliwości (na brzegach – najbliższa wartość)
        var last = -1
        for (i in 0 until nc) {
            if (!known[i]) continue
            if (last >= 0 && i - last > 1) {
                for (j in last + 1 until i) {
                    val w = (j - last).toFloat() / (i - last)
                    hRe[j] = hRe[last] + (hRe[i] - hRe[last]) * w
                    hIm[j] = hIm[last] + (hIm[i] - hIm[last]) * w
                }
            } else if (last < 0) {
                for (j in 0 until i) { hRe[j] = hRe[i]; hIm[j] = hIm[i] }
            }
            last = i
        }
        if (last < 0) return
        for (j in last + 1 until nc) { hRe[j] = hRe[last]; hIm[j] = hIm[last] }

        trackTiming(hRe, hIm, tau0)
        rotateByDelay(hRe, hIm, null, -tau0)

        // Szum: rozrzut pilotów rozproszonych względem estymaty (do SNR)
        if (s == 0 && !frameStarted) {
            startFrame()
        }
        if (!frameStarted) return
        for (i in 0 until nc) {
            val t = map.type[s][i]
            if (t != CellType.FAC && t != CellType.MSC) continue
            val p = hRe[i] * hRe[i] + hIm[i] * hIm[i]
            if (p < 1e-20f) continue
            // y·conj(H)/|H|²
            val er = (sym.re[i] * hRe[i] + sym.im[i] * hIm[i]) / p
            val ei = (sym.im[i] * hRe[i] - sym.re[i] * hIm[i]) / p
            if (t == CellType.FAC && facCount < facRe.size) {
                facRe[facCount] = er; facIm[facCount] = ei; facW[facCount] = p
                facCount++
                val d = minOf(abs(abs(er) - 0.7071f), 1f).let { it * it } + minOf(abs(abs(ei) - 0.7071f), 1f).let { it * it }
                noiseAcc += d; noiseCount++
            } else if (t == CellType.MSC && mscCount < mscRe.size) {
                mscRe[mscCount] = er; mscIm[mscCount] = ei; mscW[mscCount] = p
                mscCount++
            }
        }
        if (s == mode.symbolsPerFrame - 1) finishFrame()
    }

    private fun pilotH(sym: Symbol, i: Int): Pair<Float, Float> {
        val pr = map.pilotRe[sym.frameSym][i]
        val pi = map.pilotIm[sym.frameSym][i]
        val p2 = pr * pr + pi * pi
        return ((sym.re[i] * pr + sym.im[i] * pi) / p2) to ((sym.im[i] * pr - sym.re[i] * pi) / p2)
    }

    private fun setH(hRe: FloatArray, hIm: FloatArray, i: Int, sym: Symbol, s: Int) {
        val pr = map.pilotRe[s][i]
        val pi = map.pilotIm[s][i]
        val p2 = pr * pr + pi * pi
        hRe[i] = (sym.re[i] * pr + sym.im[i] * pi) / p2
        hIm[i] = (sym.im[i] * pr - sym.re[i] * pi) / p2
    }

    /** H[k]·e^{+j2π·b·τ/N} (b = k − c) – zdejmuje nachylenie fazy od opóźnienia τ (dla τ < 0 – przywraca). */
    private fun rotateByDelay(hRe: FloatArray, hIm: FloatArray, only: BooleanArray?, tau: Double) {
        for (i in hRe.indices) {
            if (only != null && !only[i]) continue
            val a = 2 * PI * (map.kMin + i - centerBin) * tau / n
            val c = cos(a).toFloat(); val s = sin(a).toFloat()
            val r = hRe[i] * c - hIm[i] * s
            hIm[i] = hRe[i] * s + hIm[i] * c
            hRe[i] = r
        }
    }

    /**
     * Opóźnienie sygnału w oknie FFT: [tau0] (już zdjęte z [hRe]/[hIm]) plus resztkowe nachylenie fazy.
     * Okno przesuwamy, gdy sygnał ucieka z prefiksu.
     */
    private fun trackTiming(hRe: FloatArray, hIm: FloatArray, tau0: Double) {
        var aRe = 0.0; var aIm = 0.0
        for (i in 0 until hRe.size - 1) {
            aRe += hRe[i + 1] * hRe[i] + hIm[i + 1] * hIm[i]
            aIm += hIm[i + 1] * hRe[i] - hRe[i + 1] * hIm[i]
        }
        // Okno zaczęte β próbek wcześniej: Y[b] = X[b]·e^{−j2πbβ/N}
        val tau = tau0 - atan2(aIm, aRe) * n / (2 * PI)
        tauSmooth = 0.9 * tauSmooth + 0.1 * tau
        val err = tauSmooth - drift - g / 4
        val before = drift
        if (err > 1.5) drift++ else if (err < -1.5) drift--
        if (drift != before) debugLog?.invoke("drift $before→$drift tau=${"%.2f".format(tauSmooth)} sym=${history.last().frameSym}")
    }

    private fun startFrame() {
        frameStarted = true
        facCount = 0
        mscCount = 0
        if (facRe.size != DrmTables.NUM_FAC_CELLS) {
            facRe = FloatArray(DrmTables.NUM_FAC_CELLS); facIm = FloatArray(DrmTables.NUM_FAC_CELLS); facW = FloatArray(DrmTables.NUM_FAC_CELLS)
        }
        val nm = map.mscCellsPerFrame
        if (mscRe.size != nm) {
            mscRe = FloatArray(nm); mscIm = FloatArray(nm); mscW = FloatArray(nm)
        }
        noiseAcc = 0.0; noiseCount = 0
    }

    private fun finishFrame() {
        val complete = facCount == facRe.size && mscCount == mscRe.size
        val facCells = DrmCells(facRe.copyOf(), facIm.copyOf(), facW.copyOf())
        val mscCells = DrmCells(mscRe.copyOf(), mscIm.copyOf(), mscW.copyOf())
        val noise = if (noiseCount > 0) noiseAcc / noiseCount else 1.0
        // Następna ramka zaczyna się od kolejnego symbolu
        startFrame()
        if (!complete) return
        // Wagi to |H|² – normalizujemy, żeby skala LLR nie zależała od głośności
        normalizeWeights(facCells)
        normalizeWeights(mscCells)
        facTotal++
        debugLog?.invoke(
            "frame: noise=$noise drift=$drift tau=${"%.1f".format(tauSmooth)} df=${"%.2f".format(deltaHz)} " +
                "P(msc)=${"%.2f".format((0 until mscCells.size).sumOf { (mscCells.re[it] * mscCells.re[it] + mscCells.im[it] * mscCells.im[it]).toDouble() } / mscCells.size)} " +
                "P(fac)=${"%.2f".format((0 until facCells.size).sumOf { (facCells.re[it] * facCells.re[it] + facCells.im[it] * facCells.im[it]).toDouble() } / facCells.size)} fac0..5=" +
                (0 until 6).joinToString { "(%.2f,%.2f)".format(facCells.re[it], facCells.im[it]) }
        )
        val fac = DrmChannelDecoder.decodeFac(facCells)
        if (fac == null) {
            badFacInRow++
            report()
            if (badFacInRow >= MAX_BAD_FAC) {
                locked = false
                lastAcquireAttempt = NEVER
                onStatus(DrmSyncStatus(false))
            }
            return
        }
        badFacInRow = 0
        facOk++
        snrDb = 10 * kotlin.math.log10(1.0 / noise.coerceAtLeast(1e-6))
        lastFac = fac
        if (fac.occupancy != occupancy) {
            // Zajętość z FAC jest pewniejsza niż z mocy na brzegu pasma
            occupancy = fac.occupancy
            map = DrmCellMap.of(mode, occupancy)
            interleaveHistory.clear()
            history.clear()
            frameStarted = false
            report()
            return
        }
        report()
        if (!fac.isData || mscCells.size != map.mscCellsPerFrame) return

        // Rozplot komórek: wyjście ramki t, komórka i pochodzi z wejścia t − (i mod D)
        val depth = if (fac.longInterleave) 5 else 1
        interleaveHistory += mscCells
        while (interleaveHistory.size > depth) interleaveHistory.removeAt(0)
        if (interleaveHistory.size < depth) return
        val nm = mscCells.size
        val table = BlockInterleaver.table(nm, 5)
        val out = DrmCells(FloatArray(nm), FloatArray(nm), FloatArray(nm))
        for (i in 0 until nm) {
            val src = interleaveHistory[i % depth]
            out.re[table[i]] = src.re[i]
            out.im[table[i]] = src.im[i]
            out.weight[table[i]] = src.weight[i]
        }
        val coding = MscCoding(fac.qamLevels, fac.highProtection)
        val bytes = DrmChannelDecoder.decodeMsc(out, coding)
        onFrame(bytes, fac)
    }

    private fun normalizeWeights(cells: DrmCells) {
        var sum = 0.0
        for (w in cells.weight) sum += w
        val mean = (sum / cells.size.coerceAtLeast(1)).toFloat()
        if (mean <= 0f) return
        for (i in cells.weight.indices) cells.weight[i] = cells.weight[i] / mean
    }

    private fun report() {
        onStatus(
            DrmSyncStatus(
                locked = locked, mode = mode, occupancy = occupancy, frequencyOffsetHz = deltaHz,
                snrDb = snrDb, facOkPercent = if (facTotal > 0) facOk * 100 / facTotal else 0, fac = lastFac
            )
        )
    }

    /** Diagnostyka (testy). */
    internal var debugLog: ((String) -> Unit)? = null

    companion object {
        private const val ACQ_MIX_HZ = 1575.0
        private const val NEVER = -1_000_000_000L
        private const val ACQ_SECONDS = 2
        private const val ACQ_MIN_METRIC = 0.25
        private const val MAX_BAD_FAC = 6
        private const val MAX_KEEP_SECONDS = 20
    }
}
