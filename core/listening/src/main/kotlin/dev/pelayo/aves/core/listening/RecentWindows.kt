package dev.pelayo.aves.core.listening

import dev.pelayo.aves.core.ml.api.ClassificationResult

/**
 * Las ultimas ventanas clasificadas, con su audio y lo que dijo el modelo.
 *
 * Existe para que el recorte guardado y la puntuacion guardada sean de la
 * MISMA ventana. El estabilizador confirma una especie tras varias ventanas
 * seguidas y recuerda la mejor puntuacion de la racha, pero el audio que habia
 * a mano al confirmar era el de la ultima: con rachas tipo 0,8 · 0,4 · 0,4 se
 * guardaba un 0,8 con el audio del 0,4, y la bandeja de revision ensenaba como
 * prueba un recorte que no era el que convencio al modelo.
 *
 * Guarda tambien las ventanas que no se clasificaron (silencio), sin
 * resultados: hacen falta para el preambulo del recorte
 * ([audioEndingWith]), que tiene que ser audio seguido.
 *
 * No copia nada: cada ventana que emite la captura es un array nuevo, asi que
 * guardar la referencia basta. Con 7 ventanas de 3 s a 48 kHz son ~4 MB.
 *
 * No es thread-safe: se usa desde el hilo que recolecta el flujo, como el
 * estabilizador.
 */
internal class RecentWindows(private val capacity: Int) {

    init {
        require(capacity > 0) { "capacity debe ser positiva" }
    }

    class Window(
        val samples: FloatArray,
        val endTimestampMillis: Long,
        /** Todos los candidatos de la ventana, de mas a menos probable. */
        val results: List<ClassificationResult>,
    ) {
        /** El resultado de [speciesId] en esta ventana, o null si no salio. */
        fun resultFor(speciesId: String): ClassificationResult? =
            results.firstOrNull { it.speciesId == speciesId }
    }

    private val windows = ArrayDeque<Window>(capacity)

    val size: Int get() = windows.size

    fun add(samples: FloatArray, endTimestampMillis: Long, results: List<ClassificationResult>) {
        if (windows.size == capacity) windows.removeFirst()
        windows.addLast(Window(samples, endTimestampMillis, results))
    }

    /**
     * La ventana reciente en la que [speciesId] saco mas puntuacion, o null si
     * no aparece en ninguna. Con empate gana la mas reciente.
     */
    fun bestFor(speciesId: String): Window? {
        var best: Window? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (window in windows) {
            val score = window.resultFor(speciesId)?.adjustedScore ?: continue
            if (score >= bestScore) {
                best = window
                bestScore = score
            }
        }
        return best
    }

    /**
     * El audio de [window] con hasta [preRollWindows] saltos de antes delante.
     *
     * Tres segundos cortaban el canto: el recorte empezaba a mitad de la frase
     * y sonaba a nada. Las ventanas se solapan (cada una empieza un salto
     * despues de la anterior), asi que lo que [window] no tiene de antes esta
     * en el primer salto de cada ventana previa. Solo se encadenan ventanas
     * seguidas de verdad: si falta una (se descarto por ir con retraso, o
     * sonaba un recorte de la propia app), el preambulo se corta ahi.
     *
     * @param hopSamples muestras entre dos ventanas seguidas.
     * @param hopMillis lo mismo en tiempo, para reconocer las ventanas seguidas.
     */
    fun audioEndingWith(window: Window, hopSamples: Int, hopMillis: Long, preRollWindows: Int): FloatArray {
        val index = windows.indexOf(window)
        if (index < 0 || preRollWindows <= 0 || hopSamples <= 0) return window.samples

        val previous = ArrayList<Window>(preRollWindows)
        var later = window
        var cursor = index - 1
        while (cursor >= 0 && previous.size < preRollWindows) {
            val candidate = windows[cursor]
            val gap = later.endTimestampMillis - candidate.endTimestampMillis
            if (gap <= 0 || gap > hopMillis * CONTIGUITY_TOLERANCE) break
            if (candidate.samples.size < hopSamples) break
            previous += candidate
            later = candidate
            cursor--
        }
        if (previous.isEmpty()) return window.samples

        val result = FloatArray(previous.size * hopSamples + window.samples.size)
        var offset = 0
        for (earlier in previous.asReversed()) {
            earlier.samples.copyInto(result, offset, 0, hopSamples)
            offset += hopSamples
        }
        window.samples.copyInto(result, offset)
        return result
    }

    fun clear() = windows.clear()

    private companion object {
        /** Hasta un 50 % de retraso entre dos ventanas sigue siendo "la siguiente". */
        const val CONTIGUITY_TOLERANCE = 1.5
    }
}
