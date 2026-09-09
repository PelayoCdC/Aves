package dev.pelayo.aves.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Espectrograma para pintar, no para clasificar.
 *
 * El modelo ya calcula el suyo dentro del grafo; este es para el usuario. Un
 * espectrograma en directo es la unica forma de VER que el microfono esta
 * captando un canto antes de que el modelo lo confirme, y convierte "no parecia
 * captar nada" en algo que se puede juzgar de un vistazo.
 *
 * Todo es Kotlin puro y determinista: la FFT es una radix-2 iterativa de
 * libro, y el resultado se comprime a unas pocas bandas logaritmicas, que es
 * como el oido reparte las frecuencias y como se dibujan los espectrogramas de
 * las guias de cantos.
 *
 * @param bands filas del dibujo, de graves a agudos.
 * @param maxFrequencyHz techo del dibujo. 12 kHz cubre practicamente todo canto
 *   de ave; por encima solo hay insectos y ruido.
 */
class SpectrogramComputer(
    private val sampleRate: Int,
    private val scaling: PcmScaling,
    private val fftSize: Int = DEFAULT_FFT_SIZE,
    val hopSize: Int = DEFAULT_HOP_SIZE,
    val bands: Int = DEFAULT_BANDS,
    maxFrequencyHz: Double = DEFAULT_MAX_FREQUENCY_HZ,
) {
    init {
        require(fftSize > 0 && fftSize and (fftSize - 1) == 0) { "fftSize debe ser potencia de dos" }
        require(hopSize in 1..fftSize) { "hopSize fuera de rango" }
        require(bands > 0) { "bands debe ser positivo" }
    }

    private val window = DoubleArray(fftSize) { 0.5 * (1 - cos(2 * PI * it / (fftSize - 1))) }
    private val real = DoubleArray(fftSize)
    private val imaginary = DoubleArray(fftSize)

    /** Indice de bin FFT en el que empieza cada banda, mas el final. */
    private val bandEdges: IntArray = run {
        val binHz = sampleRate.toDouble() / fftSize
        val minHz = MIN_FREQUENCY_HZ
        val maxHz = min(maxFrequencyHz, sampleRate / 2.0)
        val logMin = ln(minHz)
        val logMax = ln(maxHz)
        IntArray(bands + 1) { i ->
            val hz = kotlin.math.exp(logMin + (logMax - logMin) * i / bands)
            (hz / binHz).toInt().coerceIn(1, fftSize / 2)
        }
    }

    /**
     * Columnas del espectrograma de [samples], una por salto de [hopSize].
     *
     * Cada columna trae [bands] valores en [0, 1]: 0 es el suelo ([FLOOR_DB]
     * bajo fondo de escala) y 1 el fondo de escala. Es una escala absoluta, no
     * relativa a la ventana, para que un silencio se vea como silencio y no se
     * estire hasta parecer ruido.
     */
    fun columns(samples: FloatArray): List<FloatArray> {
        val result = ArrayList<FloatArray>()
        var start = 0
        while (start + fftSize <= samples.size) {
            result += column(samples, start)
            start += hopSize
        }
        return result
    }

    private fun column(samples: FloatArray, start: Int): FloatArray {
        val fullScale = scaling.fullScale
        for (i in 0 until fftSize) {
            real[i] = samples[start + i] / fullScale * window[i]
            imaginary[i] = 0.0
        }
        fft(real, imaginary)

        val out = FloatArray(bands)
        for (band in 0 until bands) {
            val from = bandEdges[band]
            val to = max(bandEdges[band + 1], from + 1)
            var peak = 0.0
            for (bin in from until to) {
                val magnitude = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
                if (magnitude > peak) peak = magnitude
            }
            // Magnitud relativa al fondo de escala: una senal de amplitud 1 con
            // ventana de Hann da ~fftSize/4 en su bin.
            val normalized = peak / (fftSize / 4.0)
            val db = if (normalized <= 0.0) FLOOR_DB else 20 * log10(normalized)
            out[band] = ((db - FLOOR_DB) / -FLOOR_DB).toFloat().coerceIn(0f, 1f)
        }
        return out
    }

    companion object {
        /** 1.024 muestras: a 48 kHz son 21 ms y 47 Hz por bin. Basta para pintar. */
        const val DEFAULT_FFT_SIZE = 1024

        const val DEFAULT_HOP_SIZE = 512

        const val DEFAULT_BANDS = 48

        const val DEFAULT_MAX_FREQUENCY_HZ = 12_000.0

        /** Por debajo de esto no canta nada que interese; el ruido de trafico vive ahi. */
        private const val MIN_FREQUENCY_HZ = 300.0

        /** Suelo del dibujo. Por debajo de -80 dBFS no hay nada que mirar. */
        const val FLOOR_DB = -80.0

        /**
         * FFT radix-2 en el sitio, Cooley-Tukey iterativa. Sin dependencias: el
         * proyecto no arrastra una libreria de DSP para una rutina de 30 lineas.
         */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            require(n == im.size && n > 0 && n and (n - 1) == 0) { "La FFT necesita potencia de dos" }

            // Reordenacion por inversion de bits.
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }

            var length = 2
            while (length <= n) {
                val angle = -2 * PI / length
                val wRe = cos(angle)
                val wIm = sin(angle)
                var i = 0
                while (i < n) {
                    var curRe = 1.0
                    var curIm = 0.0
                    for (k in 0 until length / 2) {
                        val uRe = re[i + k]
                        val uIm = im[i + k]
                        val vRe = re[i + k + length / 2] * curRe - im[i + k + length / 2] * curIm
                        val vIm = re[i + k + length / 2] * curIm + im[i + k + length / 2] * curRe
                        re[i + k] = uRe + vRe
                        im[i + k] = uIm + vIm
                        re[i + k + length / 2] = uRe - vRe
                        im[i + k + length / 2] = uIm - vIm
                        val nextRe = curRe * wRe - curIm * wIm
                        curIm = curRe * wIm + curIm * wRe
                        curRe = nextRe
                    }
                    i += length
                }
                length = length shl 1
            }
        }
    }
}
