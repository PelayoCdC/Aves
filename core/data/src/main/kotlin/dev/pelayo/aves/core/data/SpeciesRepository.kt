package dev.pelayo.aves.core.data

import dev.pelayo.aves.core.database.AvesDatabase
import dev.pelayo.aves.core.database.entity.SpeciesEntity
import dev.pelayo.aves.core.model.Species
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Acceso al catalogo de especies. Las features hablan con esto, nunca con Room. */
class SpeciesRepository(private val database: AvesDatabase) {

    private val speciesDao = database.speciesDao()

    suspend fun find(speciesId: String): Species? = speciesDao.findById(speciesId)?.toDomain()

    suspend fun findAll(speciesIds: List<String>): List<Species> =
        speciesDao.findAllById(speciesIds).map(SpeciesEntity::toDomain)

    fun search(query: String, limit: Int = 50): Flow<List<Species>> =
        speciesDao.search(query, limit).map { rows -> rows.map(SpeciesEntity::toDomain) }

    suspend fun catalogSize(): Int = speciesDao.count()
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
)
