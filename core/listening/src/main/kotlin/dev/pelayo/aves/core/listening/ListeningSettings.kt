package dev.pelayo.aves.core.listening

import dev.pelayo.aves.core.data.SpeciesRepository
import dev.pelayo.aves.core.data.SpeciesResolver
import dev.pelayo.aves.core.model.Species

/**
 * Lo que el usuario puede ajustar de una escucha.
 *
 * Es un tipo de `core:listening` y no el de la pantalla de ajustes a proposito:
 * este modulo no puede depender de una feature. La app traduce uno en otro al
 * arrancar cada escucha, y asi los ajustes dejan de ser una fachada con cuatro
 * controles que no llegaban a ninguna parte.
 */
data class ListeningSettings(
    /** Cuanto pesa el prior geo-temporal frente a la salida cruda del modelo, 0..1. */
    val contextInfluence: Float = DEFAULT_CONTEXT_INFLUENCE,
    /** Umbral para que una deteccion entre en la lista (ver [DetectionStabilizer]). */
    val confirmThreshold: Float = DetectionStabilizer.DEFAULT_CONFIRM_THRESHOLD,
    /** Tras cuanto se para sola. Se recorta a los limites de [ListeningDeadline]. */
    val maxDurationMillis: Long = ListeningDeadline.DEFAULT_DURATION_MILLIS,
    /** false = ni se consulta la ubicacion ni se guardan coordenadas. */
    val useLocation: Boolean = true,
) {
    /**
     * El umbral para MANTENERSE en la lista baja con el de entrada en la misma
     * proporcion que los valores por defecto: un ave que canta a ratos no debe
     * desaparecer entre trino y trino.
     */
    val keepThreshold: Float
        get() = (confirmThreshold * DetectionStabilizer.KEEP_RATIO).coerceIn(0.01f, confirmThreshold)

    companion object {
        /**
         * A la mitad y no al maximo a proposito: el prior es una estadistica, no
         * una verdad. Si mandara del todo, un divagante de verdad quedaria
         * sepultado por lo que "deberia" haber en la zona, y detectar eso es
         * justo lo mas emocionante que puede pasar con esta app.
         */
        const val DEFAULT_CONTEXT_INFLUENCE = 0.5f
    }
}

/**
 * El mejor candidato de la ULTIMA ventana, confirmado o no.
 *
 * Existe por la primera prueba de campo: la pantalla solo ensenaba lo
 * confirmado, y hasta entonces decia "nada reconocido". Con esto el usuario ve
 * que el modelo esta oyendo algo ("¿mirlo? 28 %") aunque aun no lo de por bueno,
 * y la diferencia entre "no capta" y "no confirma" se ve a simple vista.
 */
data class CandidateGlimpse(
    val speciesId: String,
    /** Etiqueta cruda del modelo, `Cientifico_Comun`. */
    val label: String,
    val score: Float,
    val commonName: String? = null,
    val scientificName: String? = null,
)

/**
 * Pone nombre del catalogo a lo que dice el modelo.
 *
 * La etiqueta cruda de BirdNET v2.4 viene en castellano, pero la de BirdNET+
 * viene en ingles ("Common Blackbird") y la del clasificador de imagen ni
 * siquiera trae nombre comun. Resolver siempre por el catalogo hace que la
 * pantalla en directo hable el mismo idioma que la Pokedex, y ademas aplica el
 * cross-walk (codigos caducados, agrupaciones) en vez del nombre provisional.
 */
class DetectionNamer(
    private val resolver: SpeciesResolver,
    private val species: SpeciesRepository,
) {
    private val cache = HashMap<Pair<String, Int>, Species?>()

    suspend fun speciesFor(modelId: String, labelIndex: Int): Species? {
        val key = modelId to labelIndex
        if (cache.containsKey(key)) return cache[key]
        val found = runCatching {
            resolver.resolve(modelId, labelIndex)?.let { species.find(it) }
        }.getOrNull()
        cache[key] = found
        return found
    }

    suspend fun name(modelId: String, detection: StableDetection): StableDetection {
        val found = speciesFor(modelId, detection.labelIndex) ?: return detection
        return detection.copy(
            canonicalSpeciesId = found.speciesId,
            commonName = found.commonName,
            scientificName = found.scientificName,
        )
    }

    suspend fun name(modelId: String, glimpse: CandidateGlimpse, labelIndex: Int): CandidateGlimpse {
        val found = speciesFor(modelId, labelIndex) ?: return glimpse
        return glimpse.copy(commonName = found.commonName, scientificName = found.scientificName)
    }
}
