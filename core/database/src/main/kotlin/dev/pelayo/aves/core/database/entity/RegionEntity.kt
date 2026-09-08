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
 * Que meses de una celda estan descargados, y cuando.
 *
 * Existe porque la descarga de una celda son doce consultas a GBIF y antes se
 * escribia todo o nada: una escucha corta con mala cobertura no cacheaba ni un
 * mes, y la celda nunca llegaba a existir. Con una fila por mes, cada consulta
 * que termina queda guardada aunque las demas se cancelen, y el mes actual (que
 * es el que hace falta YA) se pide primero.
 *
 * Un mes con cero ocurrencias es informacion real y tambien se registra aqui:
 * asi no se vuelve a pedir cada vez.
 */
@Entity(
    tableName = "region_cell_months",
    primaryKeys = ["cellId", "month"],
)
data class RegionMonthEntity(
    val cellId: String,
    /** 1..12. */
    val month: Int,
    val fetchedAt: Long,
    /** Ocurrencias totales de aves en la celda ese mes: el denominador de la rareza. */
    val totalObservations: Long,
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
