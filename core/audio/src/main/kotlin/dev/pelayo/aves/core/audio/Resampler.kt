package dev.pelayo.aves.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Remuestreo con filtro anti-aliasing.
 *
 * Antes se interpolaba linealmente y punto, con la excusa de que "casi todo el
 * material relevante ya viene a 48 kHz". Dejo de ser verdad en cuanto entro
 * BirdNET+, que trabaja a 32 kHz: TODO fichero normal (44,1 o 48 kHz) pasa por
 * aqui bajando de frecuencia, y decimar sin filtrar pliega todo lo que hay por
 * encima de 16 kHz sobre la banda audible. Un canto de reyezuelo a 8 kHz se
 * encontraria con el alias de un ruido de 24 kHz encima.
 *
 * Solucion clasica y suficiente: un paso-bajo FIR de ventana de Hamming antes
 * de interpolar. Al SUBIR de frecuencia no hay aliasing posible y se salta el
 * filtro.
 */
object Resampler {

    /** Impar, para que el filtro sea simetrico con un centro exacto. */
    private const val TAPS = 63

    /**
     * Fraccion de la nueva Nyquist en la que se corta. Deja margen a la banda
     * de transicion del filtro, que con 63 coeficientes no es un muro.
     */
    private const val CUTOFF_FRACTION = 0.45

    fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
        require(from > 0 && to > 0) { "Frecuencias invalidas: $from -> $to" }
        if (input.isEmpty() || from == to) return input

        val filtered = if (from > to) lowPass(input, cutoffHz = CUTOFF_FRACTION * to, sampleRate = from) else null
        return interpolate(filtered, input, from, to)
    }

    /**
     * Interpolacion lineal sobre la senal ya filtrada. La lineal basta una vez
     * quitado lo que podia solaparse: lo que queda por debajo del corte se
     * reconstruye con error inaudible a estas frecuencias.
     */
    private fun interpolate(filtered: DoubleArray?, raw: ShortArray, from: Int, to: Int): ShortArray {
        val ratio = from.toDouble() / to
        val outputSize = (raw.size / ratio).toInt()
        val output = ShortArray(outputSize)
        val size = raw.size
        for (i in 0 until outputSize) {
            val position = i * ratio
            val index = position.toInt()
            val fraction = position - index
            val a = if (filtered != null) filtered[index] else raw[index].toDouble()
            val b = when {
                index + 1 >= size -> a
                filtered != null -> filtered[index + 1]
                else -> raw[index + 1].toDouble()
            }
            output[i] = (a + (b - a) * fraction).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return output
    }

    /** Coeficientes de un paso-bajo FIR de ventana de Hamming, normalizados a ganancia 1 en continua. */
    internal fun lowPassKernel(cutoffHz: Double, sampleRate: Int, taps: Int = TAPS): DoubleArray {
        val fc = cutoffHz / sampleRate
        val center = (taps - 1) / 2
        val kernel = DoubleArray(taps)
        var sum = 0.0
        for (n in 0 until taps) {
            val k = n - center
            val sinc = if (k == 0) 2 * fc else sin(2 * PI * fc * k) / (PI * k)
            val window = 0.54 - 0.46 * cos(2 * PI * n / (taps - 1))
            kernel[n] = sinc * window
            sum += kernel[n]
        }
        for (n in kernel.indices) kernel[n] /= sum
        return kernel
    }

    /** Convolucion directa. Para los tamanos de aqui (segundos de audio) sobra. */
    internal fun lowPass(input: ShortArray, cutoffHz: Double, sampleRate: Int): DoubleArray {
        val kernel = lowPassKernel(cutoffHz, sampleRate)
        val half = kernel.size / 2
        val output = DoubleArray(input.size)
        for (i in input.indices) {
            var acc = 0.0
            for (k in kernel.indices) {
                val j = i + k - half
                if (j in input.indices) acc += input[j] * kernel[k]
            }
            output[i] = acc
        }
        return output
    }
}
