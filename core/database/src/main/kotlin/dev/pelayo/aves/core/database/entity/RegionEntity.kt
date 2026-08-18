package dev.pelayo.aves.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cache de ocurrencias de GBIF por celda geografica.
 *
 * Se cachea por celda geohash-4 (~40x20 km) y no por coordenada exacta para que
 * cruzar la ciudad no dispare descargas constantes. Una vez cacheada una celda,
 * la app funciona offline en esa zona indefinidamente.
 *
 * Se llena en Fase 4; la tabla existe desde ya para no tener que migrar despues.
 */
@Entity(tableName = "region_cells")
data class RegionCellEntity(
    @PrimaryKey val cellId: String,
    val centerLat: Double,
    val centerLon: Double,
    /** Total de observaciones de aves en la celda, denominador de la rareza. */
    val totalObservations: Long,
    val lastRefreshed: Long,
)

/**
 * Frecuencia de una especie en una celda y mes.
 *
 * La clave compuesta refleja el hecho central de la rareza: no es propiedad de
 * la especie, sino del trio (especie, lugar, mes).
 */
@Entity(
    tableName = "species_frequency",
    primaryKeys = ["cellId", "speciesId", "month"],
    indices = [Index("speciesId")],
)
data class SpeciesFrequencyEntity(
    val cellId: String,
    val speciesId: String,
    /** 1..12. El 0 se reserva para el agregado anual. */
    val month: Int,
    val occurrenceCount: Long,
    /** occurrenceCount / totalObservations de la celda en ese mes. */
    val share: Float,
    val rarityTier: Int,
)
