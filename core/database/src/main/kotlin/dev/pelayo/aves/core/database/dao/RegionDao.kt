package dev.pelayo.aves.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.pelayo.aves.core.database.entity.RegionCellEntity
import dev.pelayo.aves.core.database.entity.SpeciesFrequencyEntity

@Dao
interface RegionDao {

    @Query("SELECT * FROM region_cells WHERE cellId = :cellId")
    suspend fun findCell(cellId: String): RegionCellEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCell(cell: RegionCellEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFrequencies(frequencies: List<SpeciesFrequencyEntity>)

    /**
     * Todas las frecuencias de una celda y mes. Se carga entera y se cachea en
     * memoria: el prior se consulta por cada candidato de cada ventana, varias
     * veces por segundo, y una consulta a SQLite por cada uno seria absurda.
     */
    @Query("SELECT * FROM species_frequency WHERE cellId = :cellId AND month = :month")
    suspend fun frequenciesFor(cellId: String, month: Int): List<SpeciesFrequencyEntity>

    @Query(
        """
        SELECT * FROM species_frequency
        WHERE cellId = :cellId AND speciesId = :speciesId AND month = :month
        """,
    )
    suspend fun frequencyOf(cellId: String, speciesId: String, month: Int): SpeciesFrequencyEntity?

    @Query("SELECT COUNT(*) FROM species_frequency WHERE cellId = :cellId")
    suspend fun frequencyCountFor(cellId: String): Int

    @Query("DELETE FROM species_frequency WHERE cellId = :cellId")
    suspend fun deleteFrequenciesFor(cellId: String)

    /**
     * Reemplaza los datos de una celda de forma atomica.
     *
     * Si se insertara sin borrar, una revision posterior de GBIF dejaria
     * mezcladas frecuencias viejas y nuevas de la misma celda, y las rarezas
     * saldrian de una media sin sentido entre dos instantaneas.
     */
    @Transaction
    suspend fun replaceCell(cell: RegionCellEntity, frequencies: List<SpeciesFrequencyEntity>) {
        deleteFrequenciesFor(cell.cellId)
        upsertCell(cell)
        upsertFrequencies(frequencies)
    }
}
