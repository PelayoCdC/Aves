package dev.pelayo.aves.core.data

import dev.pelayo.aves.core.database.AvesDatabase
import dev.pelayo.aves.core.model.Species
import dev.pelayo.aves.core.network.GbifClient
import kotlinx.coroutines.CancellationException

/**
 * La clave de GBIF de cada especie, que es lo que pide el mapa de densidad.
 *
 * El catalogo trae `gbifSpeciesKey` vacio en todas las especies (se sembro
 * desde eBird), asi que se pregunta a GBIF por el nombre cientifico la primera
 * vez que hace falta y se guarda en la propia fila. Es una consulta de unos
 * cientos de bytes, una vez por especie y para siempre.
 */
class RangeMapRepository(
    database: AvesDatabase,
    private val gbif: GbifClient,
) {
    private val speciesDao = database.speciesDao()

    /**
     * Especies que GBIF no reconoce. Solo en memoria: que hoy no haya casado no
     * quiere decir que no vaya a casar tras una actualizacion del catalogo, y
     * una consulta por sesion no le duele a nadie.
     */
    private val misses = HashSet<String>()

    /**
     * La clave del taxon de [species] en GBIF, o null si GBIF no la reconoce.
     *
     * @throws dev.pelayo.aves.core.network.GbifUnavailableException sin red.
     *   Es distinto de "no la reconoce": sin red hay que reintentar despues.
     */
    suspend fun taxonKeyFor(species: Species): Int? {
        species.gbifSpeciesKey?.let { return it }
        speciesDao.findById(species.speciesId)?.gbifSpeciesKey?.let { return it }
        synchronized(misses) { if (species.speciesId in misses) return null }

        val key = try {
            gbif.matchSpeciesKey(species.scientificName)
        } catch (e: CancellationException) {
            throw e
        }
        if (key == null) {
            synchronized(misses) { misses += species.speciesId }
            return null
        }
        speciesDao.updateGbifSpeciesKey(species.speciesId, key)
        return key
    }
}
