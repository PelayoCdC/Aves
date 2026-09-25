package dev.pelayo.aves.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Descarta ventanas en silencio antes de gastar una inferencia en ellas.
 *
 * Motivo medido, no teorico: una inferencia cuesta ~170 ms en emulador x86_64 y
 * se estiman ~300 ms en un movil de gama media. Con una ventana cada 800 ms eso
 * es un ~40% de ciclo de trabajo sostenido en dos hilos, que se nota en la mano
 * y en la bateria.
 *
 * Y en el campo la mayor parte del tiempo NO canta nada. Calcular la energia de
 * la ventana cuesta microsegundos y evita la inmensa mayoria de las inferencias
 * inutiles. Es la optimizacion con mejor relacion beneficio/esfuerzo de todo el
 * proyecto.
 *
 * **Por banda.** Con [sampleRate], la energia se mide solo entre
 * [BAND_LOW_HZ] y [BAND_HIGH_HZ], donde canta casi todo lo que interesa. El
 * viento, el trafico y el roce del movil en el bolsillo viven por debajo de
 * 1 kHz y con la energia de toda la banda abrian la puerta sin que hubiera nada
 * que oir; al reves, un ave lejana apenas movia el total. Medir solo la banda
 * del canto ahorra inferencias con viento y deja pasar lo flojo. Sin
 * [sampleRate] (0) se mide todo, como antes.
 *
 * @param thresholdFraction RMS minimo como fraccion del fondo de escala. El
 *   valor por defecto es deliberadamente permisivo: es preferible gastar alguna
 *   inferencia de mas que perderse un ave lejana, que suena muy floja.
 */
class EnergyGate(
    private val scaling: PcmScaling,
    /** Frecuencia de las muestras. 0 mide la energia de toda la banda. */
    private val sampleRate: Int = 0,
    /** Publico para que la interfaz pueda dibujar donde esta el corte. */
    val thresholdFraction: Float =
        if (sampleRate > 0) DEFAULT_BAND_THRESHOLD_FRACTION else DEFAULT_THRESHOLD_FRACTION,
) {
    private val highPass = if (sampleRate > 0) Biquad.highPass(BAND_LOW_HZ, sampleRate) else null

    // Un paso-bajo a 10 kHz solo tiene sentido si 10 kHz cabe holgado por debajo
    // de Nyquist; a 16 kHz de muestreo ya no hay nada por encima que quitar.
    private val lowPass = if (sampleRate > 0 && BAND_HIGH_HZ < sampleRate * MAX_CUTOFF_FRACTION) {
        Biquad.lowPass(BAND_HIGH_HZ, sampleRate)
    } else {
        null
    }

    /** RMS de la ventana, normalizado a [0,1] sobre el fondo de escala, de toda la banda. */
    fun normalizedRms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        // Se acumula en Double: con 144.000 muestras al cuadrado en escala RAW
        // (hasta 32768^2 cada una) un Float se satura y da un RMS incorrecto.
        var sumOfSquares = 0.0
        for (sample in samples) {
            val normalized = sample / scaling.fullScale
            sumOfSquares += normalized.toDouble() * normalized.toDouble()
        }
        return sqrt(sumOfSquares / samples.size).toFloat()
    }

    /**
     * El nivel con el que decide la puerta: el de la banda del canto si se sabe
     * la frecuencia de muestreo, el de todo si no. Es tambien el que se ensena
     * en el medidor, para que la marca del corte signifique lo mismo.
     */
    fun level(samples: FloatArray): Float {
        val high = highPass ?: return normalizedRms(samples)
        if (samples.isEmpty()) return 0f
        high.reset()
        lowPass?.reset()
        var sumOfSquares = 0.0
        for (sample in samples) {
            var value = (sample / scaling.fullScale).toDouble()
            value = high.process(value)
            lowPass?.let { value = it.process(value) }
            sumOfSquares += value * value
        }
        return sqrt(sumOfSquares / samples.size).toFloat()
    }

    fun shouldClassify(samples: FloatArray): Boolean = level(samples) >= thresholdFraction

    /**
     * Filtro de segundo orden (RBJ, "Audio EQ Cookbook"), forma directa I.
     * Doce multiplicaciones por muestra entre los dos: unos 2 ms por ventana
     * de 3 s, frente a los cientos de la inferencia que se ahorra.
     */
    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun reset() {
            x1 = 0.0
            x2 = 0.0
            y1 = 0.0
            y2 = 0.0
        }

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        companion object {
            private const val Q = 0.7071 // Butterworth: plano en la banda de paso.

            fun highPass(cutoffHz: Double, sampleRate: Int): Biquad {
                val w0 = 2 * PI * cutoffHz / sampleRate
                val alpha = sin(w0) / (2 * Q)
                val cosW = cos(w0)
                val a0 = 1 + alpha
                return Biquad(
                    b0 = (1 + cosW) / 2 / a0,
                    b1 = -(1 + cosW) / a0,
                    b2 = (1 + cosW) / 2 / a0,
                    a1 = -2 * cosW / a0,
                    a2 = (1 - alpha) / a0,
                )
            }

            fun lowPass(cutoffHz: Double, sampleRate: Int): Biquad {
                val w0 = 2 * PI * cutoffHz / sampleRate
                val alpha = sin(w0) / (2 * Q)
                val cosW = cos(w0)
                val a0 = 1 + alpha
                return Biquad(
                    b0 = (1 - cosW) / 2 / a0,
                    b1 = (1 - cosW) / a0,
                    b2 = (1 - cosW) / 2 / a0,
                    a1 = -2 * cosW / a0,
                    a2 = (1 - alpha) / a0,
                )
            }
        }
    }

    companion object {
        /**
         * ~ -60 dBFS, para toda la banda. Por debajo de esto no hay nada que un
         * modelo pueda aprovechar; es ruido de fondo del propio microfono.
         */
        const val DEFAULT_THRESHOLD_FRACTION = 0.001f

        /**
         * ~ -66 dBFS, para la banda del canto. Mas bajo que el de toda la banda
         * porque en la banda del canto el ruido de fondo (viento, trafico) es
         * mucho menor: se puede ser mas sensible sin abrir la puerta en vano, y
         * eso es lo que deja pasar un ave lejana.
         */
        const val DEFAULT_BAND_THRESHOLD_FRACTION = 0.0005f

        /** Donde empieza el canto que interesa. Por debajo: viento, trafico, pasos. */
        const val BAND_LOW_HZ = 1_000.0

        /** Por encima canta poca cosa, y el microfono ya no ayuda. */
        const val BAND_HIGH_HZ = 10_000.0

        private const val MAX_CUTOFF_FRACTION = 0.45
    }
}
