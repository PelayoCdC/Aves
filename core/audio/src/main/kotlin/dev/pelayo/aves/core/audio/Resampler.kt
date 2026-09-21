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
 *
 * El filtro se evalua AL VUELO, solo en las posiciones que la interpolacion
 * necesita, en vez de filtrar la senal entera a un array intermedio. Con
 * grabaciones de varios minutos (que es lo que sale de una app de grabadora)
 * ese intermedio en `Double` eran mas de cien megas.
 */
object Resampler {

    /** Impar, para que el filtro sea simetrico con un centro exacto. */
    private const val TAPS = 63

    /**
     * Fraccion de la nueva Nyquist en la que se corta. Deja margen a la banda
     * de transicion del filtro, que con 63 coeficientes no es un muro.
     */
    private const val CUTOFF_FRACTION = 0.45

    /**
     * @param length cuantas muestras de [input] son validas. Permite pasar un
     *   buffer mas grande que su contenido sin copiarlo.
     */
    fun resample(input: ShortArray, from: Int, to: Int, length: Int = input.size): ShortArray {
        require(from > 0 && to > 0) { "Frecuencias invalidas: $from -> $to" }
        require(length in 0..input.size) { "length fuera de rango: $length" }
        if (length == 0) return ShortArray(0)
        if (from == to) return if (length == input.size) input else input.copyOf(length)

        val kernel = if (from > to) lowPassKernel(cutoffHz = CUTOFF_FRACTION * to, sampleRate = from) else null
        val ratio = from.toDouble() / to
        val outputSize = (length / ratio).toInt()
        val output = ShortArray(outputSize)

        // La interpolacion pide el valor filtrado en `index` y en `index + 1`,
        // y el `index + 1` de una salida suele ser el `index` de la siguiente:
        // se recuerda para no convolucionar dos veces la misma posicion.
        var cachedIndex = -1
        var cachedValue = 0.0

        for (i in 0 until outputSize) {
            val position = i * ratio
            val index = position.toInt()
            val fraction = position - index

            val a = if (index == cachedIndex) cachedValue else valueAt(input, length, index, kernel)
            val b = if (index + 1 >= length) a else valueAt(input, length, index + 1, kernel)
            cachedIndex = index + 1
            cachedValue = b

            output[i] = (a + (b - a) * fraction).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return output
    }

    /** La muestra [index], filtrada si hay filtro. Convolucion directa de 63 coeficientes. */
    private fun valueAt(input: ShortArray, length: Int, index: Int, kernel: DoubleArray?): Double {
        if (kernel == null) return input[index].toDouble()
        val half = kernel.size / 2
        var acc = 0.0
        for (k in kernel.indices) {
            val j = index + k - half
            if (j in 0 until length) acc += input[j] * kernel[k]
        }
        return acc
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
}
