package io.github.sp3wro.hamradiotoolkit.sstv.robot36

// Pliki *.java w tym pakiecie to niezmieniony dekoder Robot36 (https://github.com/xdsopl/robot36,
// gałąź v2, wersja v2.17, commit 2af2390954aa248725042ba6b1332bbcca68c357, licencja 0BSD –
// LICENSE-robot36.txt). Zmieniona jest wyłącznie linia "package". Kod w plikach *.kt odtwarza
// sposób, w jaki MainActivity Robot36 karmi dekoder, żeby wynik dekodowania był identyczny.

class CompletedImage(val pixels: IntArray, val width: Int, val height: Int)

/**
 * Jedna sesja audio (nasłuch z mikrofonu albo jeden plik) – odpowiednik MainActivity.consumeAudio(),
 * processImage() i obsługi końca pliku z Robot36. Nowy Decoder na sesję, bufory scope/obrazu
 * współdzielone między sesjami, tak jak w Robot36. Bez zależności od Androida.
 */
class Robot36Session(
    sampleRate: Int,
    channels: Int,
    private val imageBuffer: PixelBuffer,
    scopeBuffer: PixelBuffer,
    modeName: String
) {
    private val decoder = Decoder(scopeBuffer, imageBuffer, RAW_MODE, sampleRate)

    // Robot36: mono -> 0, stereo z pliku -> 3 (średnia kanałów)
    private val channelSelect = if (channels == 1) 0 else 3

    init {
        decoder.setMode(modeName)
    }

    val currentMode: Mode get() = decoder.currentMode

    fun setMode(name: String) = decoder.setMode(name)

    /**
     * [pcm] musi mieć długość dokładnie liczba_ramek × kanały (Robot36 robi Arrays.copyOf),
     * bo Decoder bierze długość bloku z rozmiaru tablicy. Decoder nadpisuje zawartość [pcm].
     * Zwraca true, gdy pojawiły się nowe linie obrazu.
     */
    fun process(pcm: FloatArray): Boolean = decoder.process(pcm, channelSelect)

    /** Koniec pliku: sekunda ciszy przez dekoder, jak Decoder.finish() w Robot36. */
    fun finish(): Boolean = decoder.finish(channelSelect)

    /** processImage() z Robot36: zwraca ukończony obraz (raz) albo null. */
    fun takeCompletedImage(): CompletedImage? {
        if (imageBuffer.line < imageBuffer.height) return null
        imageBuffer.line = -1
        val size = imageBuffer.width * imageBuffer.height
        return CompletedImage(imageBuffer.pixels.copyOf(size), imageBuffer.width, imageBuffer.height)
    }

    companion object {
        const val AUTO_MODE = "Auto"
        const val RAW_MODE = "Raw"

        // Tryby w kolejności z menu Robot36; nazwy muszą być identyczne z Mode.getName()
        val MODE_NAMES = listOf(
            RAW_MODE,
            "HF Fax",
            "Robot 36 Color",
            "Robot 72 Color",
            "PD 50",
            "PD 90",
            "PD 120",
            "PD 160",
            "PD 180",
            "PD 240",
            "PD 290",
            "Martin 1",
            "Martin 2",
            "Scottie 1",
            "Scottie 2",
            "Scottie DX",
            "Wraase SC2–180"
        )
    }
}
