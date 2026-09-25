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
 * No copia nada: cada ventana que emite la captura es un array nuevo, asi que
 * guardar la referencia basta. Con 4 ventanas de 3 s a 48 kHz son ~2,3 MB.
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

    fun clear() = windows.clear()
}
