package dev.pelayo.aves.core.listening

import dev.pelayo.aves.core.ml.api.ClassificationResult

/**
 * Una deteccion que ya ha superado la histeresis y se puede ensenar.
 *
 * DUPLICADO A PROPOSITO de `dev.pelayo.aves.feature.identify.StableDetection`.
 * El bucle de escucha tiene que poder vivir en un servicio, y un modulo `core`
 * no puede depender de un `feature` sin invertir el grafo. Cuando
 * `:feature:identify` se migre a [ListeningEngine], la copia de alli queda
 * obsoleta y debe borrarse: esta es la que se queda.
 */
data class StableDetection(
    val speciesId: String,
    val label: String,
    val score: Float,
    /**
     * Indice de la clase en el tensor del modelo.
     *
     * Se arrastra hasta aqui porque es lo que la tabla `model_labels` necesita
     * para resolver la especie canonica. El speciesId que trae el clasificador
     * es provisional: no sabe de codigos eBird caducados ni de agrupaciones
     * taxonomicas.
     */
    val labelIndex: Int,
    /** Ventanas consecutivas en las que se ha visto. Util para depurar. */
    val streak: Int,
)

/**
 * Convierte el chorro de resultados por ventana en algo que un humano pueda
 * leer.
 *
 * DUPLICADO A PROPOSITO de `dev.pelayo.aves.feature.identify.DetectionStabilizer`
 * por el mismo motivo que [StableDetection]: la histeresis es parte del bucle de
 * escucha, no de la pantalla, y el bucle ha bajado a `core` para poder correr
 * dentro de un servicio. La copia de `:feature:identify` queda obsoleta en
 * cuanto esa pantalla consuma [ListeningEngine].
 *
 * Sin esto la lista cambia 1,25 veces por segundo (una por ventana) y la
 * pantalla es un parpadeo ilegible. Es el paso que separa una demo de una app
 * usable, y ademas evita que entren en la base de datos detecciones de una sola
 * ventana, que casi siempre son ruido.
 *
 * Dos umbrales distintos a proposito:
 *  - [confirmThreshold] para ENTRAR: exigente, hacen falta [requiredConsecutive]
 *    ventanas seguidas por encima.
 *  - [keepThreshold] para MANTENERSE: mas laxo, porque un ave que canta de forma
 *    intermitente no deberia desaparecer de la lista entre trino y trino.
 *
 * No es thread-safe: se usa desde el hilo que recolecta el flujo.
 */
class DetectionStabilizer(
    private val confirmThreshold: Float = DEFAULT_CONFIRM_THRESHOLD,
    private val keepThreshold: Float = DEFAULT_KEEP_THRESHOLD,
    private val requiredConsecutive: Int = DEFAULT_REQUIRED_CONSECUTIVE,
    private val forgetAfterMisses: Int = DEFAULT_FORGET_AFTER_MISSES,
) {
    private class Tracked {
        var streak: Int = 0
        var misses: Int = 0
        var confirmed: Boolean = false
        var bestScore: Float = 0f
        var label: String = ""
        var labelIndex: Int = -1
    }

    private val tracked = LinkedHashMap<String, Tracked>()

    /**
     * Alimenta una ventana de resultados y devuelve las detecciones confirmadas,
     * de mayor a menor puntuacion.
     */
    fun update(results: List<ClassificationResult>): List<StableDetection> {
        val seen = HashSet<String>(results.size)

        for (result in results) {
            val speciesId = result.speciesId ?: continue
            val existing = tracked[speciesId]
            val threshold = if (existing?.confirmed == true) keepThreshold else confirmThreshold
            if (result.adjustedScore < threshold) continue

            seen += speciesId
            val entry = existing ?: Tracked().also { tracked[speciesId] = it }
            entry.streak++
            entry.misses = 0
            entry.label = result.rawLabel
            entry.labelIndex = result.labelIndex
            // Se guarda el maximo y no el ultimo: lo relevante es lo seguro que
            // ha llegado a estar el modelo, no como estaba en la ultima ventana.
            entry.bestScore = maxOf(entry.bestScore, result.adjustedScore)
            if (entry.streak >= requiredConsecutive) entry.confirmed = true
        }

        val iterator = tracked.entries.iterator()
        while (iterator.hasNext()) {
            val (speciesId, state) = iterator.next()
            if (speciesId in seen) continue
            state.misses++
            state.streak = 0
            if (state.misses >= forgetAfterMisses) iterator.remove()
        }

        return tracked.entries
            .filter { it.value.confirmed }
            .map { (speciesId, state) ->
                StableDetection(
                    speciesId = speciesId,
                    label = state.label,
                    score = state.bestScore,
                    labelIndex = state.labelIndex,
                    streak = state.streak,
                )
            }
            .sortedByDescending { it.score }
    }

    fun reset() = tracked.clear()

    companion object {
        /** Exigente para entrar: por debajo de esto BirdNET se equivoca a menudo. */
        const val DEFAULT_CONFIRM_THRESHOLD = 0.65f

        /** Laxo para mantenerse: los pajaros hacen pausas. */
        const val DEFAULT_KEEP_THRESHOLD = 0.4f

        /** Con salto de 800 ms, 3 ventanas son ~2,4 s de evidencia. */
        const val DEFAULT_REQUIRED_CONSECUTIVE = 3

        const val DEFAULT_FORGET_AFTER_MISSES = 4
    }
}
