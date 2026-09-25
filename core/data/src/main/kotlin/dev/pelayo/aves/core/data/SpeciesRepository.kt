package dev.pelayo.aves.core.data

import dev.pelayo.aves.core.common.SearchText
import dev.pelayo.aves.core.database.AvesDatabase
import dev.pelayo.aves.core.database.entity.SpeciesEntity
import dev.pelayo.aves.core.model.Species
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Acceso al catalogo de especies. Las features hablan con esto, nunca con Room. */
class SpeciesRepository(private val database: AvesDatabase) {

    private val speciesDao = database.speciesDao()
    private val modelLabelDao = database.modelLabelDao()

    suspend fun find(speciesId: String): Species? = speciesDao.findById(speciesId)?.toDomain()

    suspend fun findAll(speciesIds: List<String>): List<Species> =
        speciesDao.findAllById(speciesIds).map(SpeciesEntity::toDomain)

    /**
     * Busca en el catalogo ENTERO, no solo en lo coleccionado, sin distinguir
     * mayusculas ni tildes. Con menos de dos caracteres no se busca: devolveria
     * medio catalogo y no serviria para nada.
     */
    fun search(query: String, limit: Int = 50): Flow<List<Species>> {
        val folded = SearchText.fold(query)
        if (folded.length < MIN_QUERY_LENGTH) return flowOf(emptyList())
        return speciesDao.search(folded, limit).map { rows -> rows.map(SpeciesEntity::toDomain) }
    }

    /** Todo el catalogo: la taxonomia entera. Es mas de lo que ningun modelo detecta. */
    suspend fun catalogSize(): Int = speciesDao.count()

    /**
     * Especies que algun modelo enlazado puede identificar: el "total" honesto de
     * la Pokedex. Prometer el catalogo entero seria prometer huecos que ningun
     * clasificador de la app podria llenar jamas.
     */
    fun detectableSpeciesCount(): Flow<Int> = modelLabelDao.detectableSpeciesCount()

    /** Lo que algun modelo enlazado sabe identificar, para cruzar con listas de fuera. */
    suspend fun detectableSpeciesIds(): Set<String> = modelLabelDao.detectableSpeciesIds().toHashSet()

    /** Guarda lo que trajo la enciclopedia para no volver a pedirlo. */
    suspend fun saveEncyclopedia(
        speciesId: String,
        imageUrl: String?,
        wikipediaUrl: String?,
        summary: String?,
        imageAttribution: String?,
    ) = speciesDao.updateEncyclopedia(speciesId, imageUrl, wikipediaUrl, summary, imageAttribution)

    private companion object {
        const val MIN_QUERY_LENGTH = 2
    }
}

internal fun SpeciesEntity.toDomain() = Species(
    speciesId = speciesId,
    scientificName = scientificName,
    genus = genus,
    family = family,
    order = order,
    commonName = defaultCommonName,
    ebirdCode = ebirdCode,
    gbifSpeciesKey = gbifSpeciesKey,
    imageUrl = imageUrl,
    wikipediaUrl = wikipediaUrl,
    summary = summary,
    imageAttribution = imageAttribution,
    taxonOrder = taxonOrder,
)
