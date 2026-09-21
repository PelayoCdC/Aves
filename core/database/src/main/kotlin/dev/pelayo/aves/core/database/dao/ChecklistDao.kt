package dev.pelayo.aves.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.pelayo.aves.core.database.entity.PokedexPreferencesEntity
import dev.pelayo.aves.core.database.entity.RegionChecklistEntity
import dev.pelayo.aves.core.database.entity.RegionChecklistSpeciesEntity
import dev.pelayo.aves.core.database.entity.SpeciesEntity
import kotlinx.coroutines.flow.Flow

/** Una especie de la lista de una zona, con su ficha del catalogo. */
data class RegionSpeciesRow(
    @Embedded val species: SpeciesEntity,
    val occurrenceCount: Long,
    val share: Float,
)

@Dao
interface ChecklistDao {

    @Query("SELECT * FROM region_checklists WHERE regionId = :regionId")
    suspend fun findChecklist(regionId: String): RegionChecklistEntity?

    @Query("SELECT * FROM region_checklists WHERE regionId = :regionId")
    fun observeChecklist(regionId: String): Flow<RegionChecklistEntity?>

    /**
     * Las especies de una zona que estan en el catalogo Y que algun modelo
     * enlazado sabe identificar.
     *
     * Las dos condiciones importan. Sin la primera saldrian nombres de GBIF que
     * no casan con ninguna ficha; sin la segunda, la Pokedex prometeria huecos
     * que ningun clasificador de la app podria llenar nunca.
     */
    @Query(
        """
        SELECT s.*, c.occurrenceCount AS occurrenceCount, c.share AS share
        FROM region_checklist_species c
        JOIN species s ON s.speciesId = c.speciesId
        WHERE c.regionId = :regionId
          AND s.speciesId IN (SELECT DISTINCT speciesId FROM model_labels WHERE speciesId IS NOT NULL)
        """,
    )
    fun speciesInRegion(regionId: String): Flow<List<RegionSpeciesRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChecklist(checklist: RegionChecklistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSpecies(species: List<RegionChecklistSpeciesEntity>)

    @Query("DELETE FROM region_checklist_species WHERE regionId = :regionId")
    suspend fun deleteSpeciesFor(regionId: String)

    /** Atomico: una lista a medio escribir daria un "te faltan 12" que es mentira. */
    @Transaction
    suspend fun replaceChecklist(
        checklist: RegionChecklistEntity,
        species: List<RegionChecklistSpeciesEntity>,
    ) {
        deleteSpeciesFor(checklist.regionId)
        upsertChecklist(checklist)
        if (species.isNotEmpty()) upsertSpecies(species)
    }

    @Query("SELECT * FROM pokedex_preferences WHERE id = 0")
    fun preferences(): Flow<PokedexPreferencesEntity?>

    @Query("SELECT * FROM pokedex_preferences WHERE id = 0")
    suspend fun currentPreferences(): PokedexPreferencesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreferences(preferences: PokedexPreferencesEntity)
}
