package dev.pelayo.aves.core.listening

import dev.pelayo.aves.core.audio.ScoredWindow
import dev.pelayo.aves.core.ml.api.ClassificationResult

/**
 * Una especie que el analisis de un fichero da por SEGURA, con las ventanas en
 * las que se oyo (que es de donde sale su pista).
 */
data class FileDetection(
    val detection: StableDetection,
    /** Las ventanas que superaron el umbral, en orden de aparicion. */
    val windows: List<ScoredWindow>,
)

/**
 * Decide que especies hay DE VERDAD en un fichero.
 *
 * La primera version ensenaba todo lo que el modelo hubiera mencionado en alguna
 * ventana, ordenado por su mejor puntuacion: una lista de veinte especies con
 * porcentajes del 6 %, que es exactamente lo que no quiere ver nadie. Un
 * clasificador de 6.000 clases siempre reparte algo de probabilidad; eso no son
 * aves, es ruido con nombre.
 *
 * Aqui solo sale lo que convence, que es el equivalente para un fichero de la
 * histeresis de la escucha en directo:
 *
 *  - la especie supera el umbral en **dos ventanas o mas** (un canto real dura, y
 *    las ventanas se solapan: que aparezca en una sola y floja es casualidad), o
 *  - la supera en **una sola pero con rotundidad** ([strongThreshold]): un
 *    reclamo aislado y clarisimo tambien es una deteccion.
 *
 * Lo que se ensena es la lista, como hace Merlin; el porcentaje solo si el
 * usuario enciende "Mostrar confianza". La puntuacion se guarda siempre, para
 * ordenar y para la base de datos.
 *
 * @param confirmThreshold el mismo umbral que usa la escucha en directo.
 */
class FileAnalysisAccumulator(
    private val confirmThreshold: Float = DetectionStabilizer.DEFAULT_CONFIRM_THRESHOLD,
    private val strongThreshold: Float = DEFAULT_STRONG_THRESHOLD,
    private val minWindows: Int = DEFAULT_MIN_WINDOWS,
) {
    private class Tally(var best: ClassificationResult) {
        val windows = ArrayList<ScoredWindow>()
    }

    private val tallies = LinkedHashMap<String, Tally>()

    /** Apunta lo que dijo el modelo de la ventana que empieza en [startSample]. */
    fun add(startSample: Int, results: List<ClassificationResult>) {
        for (result in results) {
            val speciesId = result.speciesId ?: continue
            if (result.adjustedScore < confirmThreshold) continue
            val tally = tallies.getOrPut(speciesId) { Tally(result) }
            if (result.adjustedScore > tally.best.adjustedScore) tally.best = result
            tally.windows += ScoredWindow(startSample, result.adjustedScore)
        }
    }

    /** Las especies seguras hasta ahora, la mas clara primero. */
    fun confirmed(): List<FileDetection> = tallies.values
        .filter { it.windows.size >= minWindows || it.best.adjustedScore >= strongThreshold }
        .sortedByDescending { it.best.adjustedScore }
        .map { tally ->
            FileDetection(
                detection = StableDetection(
                    speciesId = tally.best.speciesId.orEmpty(),
                    label = tally.best.rawLabel,
                    score = tally.best.adjustedScore,
                    labelIndex = tally.best.labelIndex,
                    streak = tally.windows.size,
                ),
                windows = tally.windows.toList(),
            )
        }

    companion object {
        /** Con esto basta una sola ventana. */
        const val DEFAULT_STRONG_THRESHOLD = 0.7f

        const val DEFAULT_MIN_WINDOWS = 2
    }
}
