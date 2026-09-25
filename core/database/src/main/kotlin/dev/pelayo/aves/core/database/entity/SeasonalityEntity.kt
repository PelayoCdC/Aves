package dev.pelayo.aves.core.database.entity

import androidx.room.Entity

/**
 * Cuantos registros tiene una especie cada mes alrededor de una celda: la
 * forma de su ano en ese sitio (residente, estival, invernante, de paso).
 *
 * Una fila por (especie, celda) con los doce meses juntos y no doce filas: se
 * lee y se pinta siempre entero, y asi la cache es una sola consulta.
 */
@Entity(
    tableName = "species_seasonality",
    primaryKeys = ["speciesId", "cellId"],
)
data class SeasonalityEntity(
    val speciesId: String,
    /** Geohash-4, la misma celda que la rareza. */
    val cellId: String,
    /** Doce numeros separados por comas, de enero a diciembre. */
    val monthlyCounts: String,
    val fetchedAt: Long,
)
