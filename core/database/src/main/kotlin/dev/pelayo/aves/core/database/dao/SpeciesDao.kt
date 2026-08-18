package dev.pelayo.aves.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.pelayo.aves.core.database.entity.ModelLabelEntity
import dev.pelayo.aves.core.database.entity.SpeciesEntity
import dev.pelayo.aves.core.database.entity.SpeciesNameEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SpeciesDao {

    @Query("SELECT * FROM species WHERE speciesId = :speciesId")
    suspend fun findById(speciesId: String): SpeciesEntity?

    @Query("SELECT * FROM species WHERE speciesId IN (:speciesIds)")
    suspend fun findAllById(speciesIds: List<String>): List<SpeciesEntity>

    @Query("SELECT * FROM species WHERE ebirdCode = :ebirdCode LIMIT 1")
    suspend fun findByEbirdCode(ebirdCode: String): SpeciesEntity?

    @Query("SELECT COUNT(*) FROM species")
    suspend fun count(): Int

    @Query(
        """
        SELECT * FROM species
        WHERE defaultCommonName LIKE '%' || :query || '%'
           OR scientificName LIKE '%' || :query || '%'
        ORDER BY defaultCommonName
        LIMIT :limit
        """,
    )
    fun search(query: String, limit: Int = 50): Flow<List<SpeciesEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(species: List<SpeciesEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNames(names: List<SpeciesNameEntity>)

    @Query("SELECT commonName FROM species_names WHERE speciesId = :speciesId AND languageTag = :languageTag")
    suspend fun localizedName(speciesId: String, languageTag: String): String?
}

@Dao
interface ModelLabelDao {

    /**
     * Devuelve el mapeo completo de un modelo en una sola consulta.
     *
     * Se carga entero y se cachea en memoria porque la inferencia lo consulta
     * varias veces por segundo: una consulta a SQLite por cada candidato de cada
     * ventana seria un despropósito. Son 6.522 filas, caben de sobra.
     */
    @Query("SELECT * FROM model_labels WHERE modelId = :modelId ORDER BY labelIndex")
    suspend fun labelsFor(modelId: String): List<ModelLabelEntity>

    @Query("SELECT COUNT(*) FROM model_labels WHERE modelId = :modelId")
    suspend fun countFor(modelId: String): Int

    /** Cuantas clases del modelo NO tienen especie canonica asignada. */
    @Query("SELECT COUNT(*) FROM model_labels WHERE modelId = :modelId AND speciesId IS NULL")
    suspend fun unmappedCountFor(modelId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(labels: List<ModelLabelEntity>)

    @Transaction
    suspend fun replaceModel(modelId: String, labels: List<ModelLabelEntity>) {
        deleteModel(modelId)
        upsertAll(labels)
    }

    @Query("DELETE FROM model_labels WHERE modelId = :modelId")
    suspend fun deleteModel(modelId: String)
}
