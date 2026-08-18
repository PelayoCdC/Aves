package dev.pelayo.aves.core.model

/** Como se registro un avistamiento. */
enum class ObservationSource {
    AUDIO,
    IMAGE,
    MANUAL,
}

/**
 * Un avistamiento registrado: la unidad de la "Pokedex".
 *
 * Se guardan DOS confianzas a proposito. [rawConfidence] es lo que dijo el
 * modelo y [adjustedConfidence] es el resultado tras aplicar el prior
 * geo-temporal. Separarlas permite auditar despues si el filtro de contexto
 * esta ayudando o estorbando, cosa imposible si solo se guarda el numero final.
 *
 * [modelId] se guarda por trazabilidad: cuando se cambie de modelo, hay que
 * poder saber que identificaciones vienen de cual.
 */
data class Observation(
    val id: Long = 0,
    val speciesId: String,
    val timestamp: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val source: ObservationSource,
    val rawConfidence: Float,
    val adjustedConfidence: Float,
    val modelId: String,
    val mediaPath: String? = null,
    val userConfirmed: Boolean = false,
    val notes: String? = null,
    val rarityTierAtCapture: RarityTier,
)
