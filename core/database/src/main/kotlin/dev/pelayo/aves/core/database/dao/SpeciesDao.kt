package dev.pelayo.aves.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.pelayo.aves.core.database.entity.ModelLabelEntity
import dev.pelayo.aves.core.database.entity.SpeciesEntity
import dev.pelayo.aves.core.database.entity.SpeciesNameEntity
import kotlinx.coroutines.flow.Flow

/**
 * Las columnas del catalogo que vienen del TSV.
 *
 * Es una entidad parcial para que volver a sembrar (cuando el catalogo crece
 * con una taxonomia nueva) actualice la taxonomia SIN pisar lo que la app ha
 * ido cacheando en las demas columnas: el resumen y la imagen de Wikipedia se
 * descargaron una vez y no hay motivo para perderlos.
 */
data class SpeciesTaxonomy(
    val speciesId: String,
    val scientificName: String,
    val genus: String,
    val family: String,
    val order: String,
    val defaultCommonName: String,
    val ebirdCode: String?,
    val searchName: String,
)

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

    /**
     * Busqueda por texto ya normalizado (ver `SearchText.fold`): el llamante
     * normaliza la consulta igual que se normalizo la columna al sembrar.
     */
    @Query(
        """
        SELECT * FROM species
        WHERE searchName LIKE '%' || :foldedQuery || '%'
        ORDER BY defaultCommonName
        LIMIT :limit
        """,
    )
    fun search(foldedQuery: String, limit: Int = 50): Flow<List<SpeciesEntity>>

    /**
     * Todo lo que algun modelo enlazado sabe identificar: la Pokedex "del
     * mundo" cuando se piden tambien las que faltan.
     */
    @Query(
        """
        SELECT * FROM species
        WHERE speciesId IN (SELECT DISTINCT speciesId FROM model_labels WHERE speciesId IS NOT NULL)
        """,
    )
    fun detectableSpecies(): Flow<List<SpeciesEntity>>

    @Upsert(entity = SpeciesEntity::class)
    suspend fun upsertTaxonomy(rows: List<SpeciesTaxonomy>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNames(names: List<SpeciesNameEntity>)

    @Query("SELECT commonName FROM species_names WHERE speciesId = :speciesId AND languageTag = :languageTag")
    suspend fun localizedName(speciesId: String, languageTag: String): String?

    /** La clave del taxon en GBIF, casada por nombre la primera vez que el mapa la pide. */
    @Query("UPDATE species SET gbifSpeciesKey = :gbifSpeciesKey WHERE speciesId = :speciesId")
    suspend fun updateGbifSpeciesKey(speciesId: String, gbifSpeciesKey: Int)

    /** Guarda lo que trajo Wikipedia. Se escribe una vez y se lee siempre. */
    @Query(
        """
        UPDATE species
        SET imageUrl = :imageUrl, wikipediaUrl = :wikipediaUrl, summary = :summary,
            imageAttribution = :imageAttribution
        WHERE speciesId = :speciesId
        """,
    )
    suspend fun updateEncyclopedia(
        speciesId: String,
        imageUrl: String?,
        wikipediaUrl: String?,
        summary: String?,
        imageAttribution: String?,
    )
}

@Dao
interface ModelLabelDao {

    /**
     * Devuelve el mapeo completo de un modelo en una sola consulta.
     *
     * Se carga entero y se cachea en memoria porque la inferencia lo consulta
     * varias veces por segundo: una consulta a SQLite por cada candidato de cada
     * ventana seria un despropósito. Son unos miles de filas, caben de sobra.
     */
    @Query("SELECT * FROM model_labels WHERE modelId = :modelId ORDER BY labelIndex")
    suspend fun labelsFor(modelId: String): List<ModelLabelEntity>

    @Query("SELECT COUNT(*) FROM model_labels WHERE modelId = :modelId")
    suspend fun countFor(modelId: String): Int

    /** Cuantas clases del modelo NO tienen especie canonica asignada. */
    @Query("SELECT COUNT(*) FROM model_labels WHERE modelId = :modelId AND speciesId IS NULL")
    suspend fun unmappedCountFor(modelId: String): Int

    @Query("SELECT DISTINCT modelId FROM model_labels")
    suspend fun modelIds(): List<String>

    /**
     * Especies distintas que ALGUN modelo enlazado sabe identificar.
     *
     * Es el "total" de la Pokedex: el catalogo tiene 11.000 especies, pero
     * prometer 11.000 huecos cuando el modelo solo distingue 6.400 seria
     * prometer una coleccion imposible de completar.
     */
    @Query("SELECT COUNT(DISTINCT speciesId) FROM model_labels WHERE speciesId IS NOT NULL")
    fun detectableSpeciesCount(): Flow<Int>

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
