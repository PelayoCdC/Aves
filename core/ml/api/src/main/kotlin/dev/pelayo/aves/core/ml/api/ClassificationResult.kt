package dev.pelayo.aves.core.ml.api

/**
 * Un candidato devuelto por un clasificador.
 *
 * [speciesId] ya viene traducido a la clave canonica del proyecto: nadie fuera
 * de la capa de ML llega a ver un indice de tensor. Puede ser null cuando la
 * clase del modelo no mapea a ninguna especie (ruido de fondo, voz humana,
 * hibridos, subespecies que no seguimos).
 *
 * [labelIndex] y [rawLabel] se conservan solo para depurar y para auditar el
 * cross-walk entre taxonomias.
 */
data class ClassificationResult(
    val speciesId: String?,
    val rawLabel: String,
    val labelIndex: Int,
    /** Probabilidad cruda del modelo, antes de aplicar el prior geo-temporal. */
    val rawScore: Float,
    /** Probabilidad tras aplicar el prior. Igual a [rawScore] si no hubo contexto. */
    val adjustedScore: Float,
)
