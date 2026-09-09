package dev.pelayo.aves.core.audio

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
 * @param thresholdFraction RMS minimo como fraccion del fondo de escala. El
 *   valor por defecto es deliberadamente permisivo: es preferible gastar alguna
 *   inferencia de mas que perderse un ave lejana, que suena muy floja.
 */
class EnergyGate(
    private val scaling: PcmScaling,
    /** Publico para que la interfaz pueda dibujar donde esta el corte. */
    val thresholdFraction: Float = DEFAULT_THRESHOLD_FRACTION,
) {
    /** RMS de la ventana, normalizado a [0,1] sobre el fondo de escala. */
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

    fun shouldClassify(samples: FloatArray): Boolean =
        normalizedRms(samples) >= thresholdFraction

    companion object {
        /**
         * ~ -60 dBFS. Por debajo de esto no hay nada que un modelo pueda
         * aprovechar; es ruido de fondo del propio microfono.
         */
        const val DEFAULT_THRESHOLD_FRACTION = 0.001f
    }
}
