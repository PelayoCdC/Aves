package dev.pelayo.aves.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.pelayo.aves.core.database.entity.RegionCellEntity
import dev.pelayo.aves.core.database.entity.RegionMonthEntity
import dev.pelayo.aves.core.database.entity.SpeciesFrequencyEntity

@Dao
interface RegionDao {

    @Query("SELECT * FROM region_cells WHERE cellId = :cellId")
    suspend fun findCell(cellId: String): RegionCellEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCell(cell: RegionCellEntity)

    @Query("SELECT * FROM region_cell_months WHERE cellId = :cellId AND month = :month")
    suspend fun findMonth(cellId: String, month: Int): RegionMonthEntity?

    @Query("SELECT * FROM region_cell_months WHERE cellId = :cellId ORDER BY month")
    suspend fun monthsFor(cellId: String): List<RegionMonthEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMonth(month: RegionMonthEntity)

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

    @Query("DELETE FROM species_frequency WHERE cellId = :cellId AND month = :month")
    suspend fun deleteFrequenciesFor(cellId: String, month: Int)

    /**
     * Reemplaza los datos de UN mes de una celda de forma atomica.
     *
     * Por mes y no por celda entera: asi cada consulta a GBIF que termina queda
     * guardada aunque las once restantes se cancelen. Y si se insertara sin
     * borrar, una revision posterior dejaria mezcladas frecuencias viejas y
     * nuevas del mismo mes, y las rarezas saldrian de una media sin sentido.
     */
    @Transaction
    suspend fun replaceMonth(
        cell: RegionCellEntity,
        month: RegionMonthEntity,
        frequencies: List<SpeciesFrequencyEntity>,
    ) {
        upsertCell(cell)
        deleteFrequenciesFor(cell.cellId, month.month)
        if (frequencies.isNotEmpty()) upsertFrequencies(frequencies)
        upsertMonth(month)
    }
}
