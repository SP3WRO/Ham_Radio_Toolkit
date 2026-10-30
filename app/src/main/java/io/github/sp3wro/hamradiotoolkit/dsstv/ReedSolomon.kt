package io.github.sp3wro.hamradiotoolkit.dsstv

/**
 * Kod Reeda-Solomona (255, k) nad GF(2^8) z wielomianem x^8+x^4+x^3+x^2+1 i pierwiastkami α^1…α^(255−k) –
 * parametry kodu RS1–RS4 używanego przez QSSTV/EasyPal do odtwarzania brakujących segmentów obrazka.
 * Dekoder błędów i wymazań (Berlekamp-Massey + Chien + Forney), implementacja własna.
 */
internal class ReedSolomon(private val k: Int) {
    private val n = 255
    private val nroots = n - k

    /** Koduje k bajtów danych: wynik [dane][parzystość] jest słowem kodowym (przesunięcie cykliczne postaci systematycznej). */
    fun encode(data: IntArray): IntArray {
        // c(x) = d(x)·x^(n−k) + (d(x)·x^(n−k) mod g(x)); d[i] przy x^(i+n−k)
        val parity = IntArray(nroots)
        for (i in k - 1 downTo 0) {
            val feedback = data[i] xor parity[nroots - 1]
            for (j in nroots - 1 downTo 1) parity[j] = parity[j - 1] xor Gf.mul(feedback, gen[j])
            parity[0] = Gf.mul(feedback, gen[0])
        }
        return data.copyOf(k) + parity
    }

    private val gen: IntArray = IntArray(nroots + 1).also { g ->
        g[0] = 1
        for (i in 0 until nroots) {
            // g(x) ← g(x)·(x + α^(1+i))
            val root = Gf.exp(1 + i)
            for (j in i + 1 downTo 1) g[j] = g[j - 1] xor Gf.mul(g[j], root)
            g[0] = Gf.mul(g[0], root)
        }
    }

    /**
     * Poprawia [block] (255 bajtów, b[j] przy x^j) w miejscu. [erasures] – znane pozycje uszkodzone.
     * Zwraca liczbę poprawionych symboli albo −1, gdy nie da się poprawić.
     */
    fun decode(block: IntArray, erasures: IntArray = IntArray(0)): Int {
        require(block.size == n)
        // Syndromy S_i = B(α^i), i = 1..nroots
        val s = IntArray(nroots) { i ->
            var acc = 0
            val a = Gf.exp(i + 1)
            for (j in n - 1 downTo 0) acc = Gf.mul(acc, a) xor block[j]
            acc
        }
        if (s.all { it == 0 }) return 0
        if (erasures.size > nroots) return -1

        // Wielomian lokatorów: start od wymazań Γ(x) = Π(1 + α^e·x)
        var lambda = IntArray(nroots + 1).also { it[0] = 1 }
        for (e in erasures) {
            val x = Gf.exp(e)
            for (j in nroots downTo 1) lambda[j] = lambda[j] xor Gf.mul(lambda[j - 1], x)
        }
        // Berlekamp-Massey z uwzględnieniem wymazań (krok r = 1..nroots, S_r = s[r−1])
        var b = lambda.copyOf()
        var el = erasures.size
        val noEras = erasures.size
        for (r in noEras + 1..nroots) {
            var delta = 0
            for (i in 0 until r) delta = delta xor Gf.mul(lambda[i], s[r - i - 1])
            if (delta == 0) {
                b = shiftUp(b)
                continue
            }
            val t = IntArray(nroots + 1)
            t[0] = lambda[0]
            for (i in 0 until nroots) t[i + 1] = lambda[i + 1] xor Gf.mul(delta, b[i])
            if (2 * el <= r + noEras - 1) {
                el = r + noEras - el
                val inv = Gf.inv(delta)
                b = IntArray(nroots + 1) { Gf.mul(lambda[it], inv) }
            } else {
                b = shiftUp(b)
            }
            lambda = t
        }
        var degLambda = 0
        for (i in lambda.indices) if (lambda[i] != 0) degLambda = i

        // Chien: pierwiastki λ(x) to X_j^{-1} = α^{−pos}
        val positions = ArrayList<Int>()
        for (pos in 0 until n) {
            val xinv = Gf.exp(255 - pos)
            var v = 0
            var p = 1
            for (i in 0..degLambda) {
                v = v xor Gf.mul(lambda[i], p)
                p = Gf.mul(p, xinv)
            }
            if (v == 0) positions += pos
        }
        if (positions.size != degLambda) return -1

        // Ω(x) = S(x)·λ(x) mod x^nroots, S(x) = S_1 + S_2·x + …
        val omega = IntArray(nroots)
        for (i in 0 until nroots) {
            var acc = 0
            for (j in 0..minOf(i, degLambda)) acc = acc xor Gf.mul(s[i - j], lambda[j])
            omega[i] = acc
        }
        // Forney (pierwszy pierwiastek α^1): e = Ω(X^{-1}) / λ'(X^{-1})
        for (pos in positions) {
            val xinv = Gf.exp(255 - pos)
            var num = 0
            var p = 1
            for (i in 0 until nroots) {
                num = num xor Gf.mul(omega[i], p)
                p = Gf.mul(p, xinv)
            }
            var den = 0
            p = 1
            for (i in 1..degLambda step 2) {
                // pochodna w GF(2): tylko nieparzyste potęgi, λ_i·x^(i−1)
                den = den xor Gf.mul(lambda[i], p)
                p = Gf.mul(p, Gf.mul(xinv, xinv))
            }
            if (den == 0) return -1
            block[pos] = block[pos] xor Gf.div(num, den)
        }
        return positions.size
    }

    private fun shiftUp(p: IntArray): IntArray = IntArray(p.size) { if (it == 0) 0 else p[it - 1] }

    internal object Gf {
        private val expTable = IntArray(512)
        private val logTable = IntArray(256)

        init {
            var x = 1
            for (i in 0 until 255) {
                expTable[i] = x
                logTable[x] = i
                x = x shl 1
                if (x and 0x100 != 0) x = x xor 0x11D
            }
            for (i in 255 until 512) expTable[i] = expTable[i - 255]
        }

        fun exp(e: Int): Int = expTable[((e % 255) + 255) % 255]
        fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else expTable[logTable[a] + logTable[b]]
        fun div(a: Int, b: Int): Int = if (a == 0) 0 else expTable[(logTable[a] - logTable[b] + 255) % 255]
        fun inv(a: Int): Int = expTable[(255 - logTable[a]) % 255]
    }
}

/** Pliki ".rs1"–".rs4" QSSTV: przeplot bajtów, bloki RS(255,k), nagłówek 7 bajtów z rozszerzeniem pliku. */
internal object RsFileCodec {
    private val DATA_SIZE = mapOf("rs1" to 224, "rs2" to 192, "rs3" to 160, "rs4" to 128)

    fun isRsExtension(ext: String) = ext.lowercase() in DATA_SIZE

    class Result(val bytes: ByteArray, val extension: String, val corrected: Int)

    /**
     * [received] – odebrana treść (brakujące segmenty wypełnione zerami), [missing] – indeksy bajtów
     * z brakujących segmentów. Zwraca odtworzony plik albo null, gdy brakuje za dużo danych.
     */
    fun decode(received: ByteArray, ext: String, missing: BooleanArray?): Result? {
        val k = DATA_SIZE[ext.lowercase()] ?: return null
        var rows = received.size / 255
        if (received.size % 255 != 0) rows++
        if (rows == 0) return null
        val total = rows * 255
        val rs = ReedSolomon(k)
        // Odwrócenie przeplotu: bajt n pliku trafia do bloku n mod rows, pozycji n / rows
        val blocks = Array(rows) { IntArray(255) }
        val erased = Array(rows) { ArrayList<Int>() }
        for (i in 0 until total) {
            val v = if (i < received.size) received[i].toInt() and 0xFF else 0
            blocks[i % rows][i / rows] = v
            if (missing != null && i < missing.size && missing[i]) erased[i % rows] += i / rows
        }
        var corrected = 0
        val out = java.io.ByteArrayOutputStream(rows * k)
        for (r in 0 until rows) {
            val res = rs.decode(blocks[r], erased[r].toIntArray())
            if (res < 0) return null
            corrected += res
            for (j in 0 until k) out.write(blocks[r][j])
        }
        val data = out.toByteArray()
        if (data.size < 7) return null
        val coded = (data[1].toInt() and 0xFF) + (data[2].toInt() and 0xFF) * 256
        if (coded != rows) return null
        val size = rows * k - (data[0].toInt() and 0xFF)
        val extension = String(data, 3, 3, Charsets.ISO_8859_1).trimEnd('\u0000').let { if (it == "avi") "avif" else it }
        val end = minOf(7 + size, data.size)
        return Result(data.copyOfRange(7, end), extension, corrected)
    }

    /** Kodowanie jak w nadajniku – do testów. */
    fun encode(file: ByteArray, ext: String): ByteArray {
        val k = DATA_SIZE.getValue(ext)
        val got = file.size
        var chunks = (got + 7) / k
        if ((got + 7) % k > 0) chunks++
        val plain = ByteArray(chunks * k)
        plain[0] = (k - got % k).toByte()
        plain[1] = (chunks % 256).toByte()
        plain[2] = (chunks / 256).toByte()
        val suffix = ext.padEnd(3, '\u0000')
        for (i in 0 until 3) plain[3 + i] = suffix[i].code.toByte()
        System.arraycopy(file, 0, plain, 7, minOf(got, plain.size - 7))
        val rs = ReedSolomon(k)
        val ec = IntArray(chunks * 255)
        for (c in 0 until chunks) {
            val block = rs.encode(IntArray(k) { plain[c * k + it].toInt() and 0xFF })
            System.arraycopy(block, 0, ec, c * 255, 255)
        }
        val out = ByteArray(chunks * 255)
        for (n in ec.indices) out[(n % 255) * chunks + n / 255] = ec[n].toByte()
        return out
    }
}
