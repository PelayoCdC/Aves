package dev.pelayo.aves.di

import dev.pelayo.aves.core.data.SpeciesRepository
import dev.pelayo.aves.core.data.SpeciesResolver
import dev.pelayo.aves.feature.camera.ImageCandidate
import dev.pelayo.aves.feature.camera.ImageIdentification

/**
 * Pone nombre del catalogo a lo que dice el clasificador de imagen.
 *
 * El labelmap de AIY solo trae el nombre cientifico, y la lista de candidatos
 * salia en latin: "Erithacus rubecula" en vez de "Petirrojo europeo". El
 * catalogo sabe las dos cosas, y de paso da el speciesId canonico (via
 * `model_labels`), que es el que hace falta para abrir la ficha correcta antes
 * de guardar nada.
 */
class ImageCandidateNamer(
    private val resolver: SpeciesResolver,
    private val species: SpeciesRepository,
) {
    suspend fun name(modelId: String, candidates: List<ImageCandidate>): List<ImageCandidate> =
        candidates.map { candidate ->
            val canonical = resolver.resolve(modelId, candidate.labelIndex) ?: candidate.speciesId
            val found = canonical?.let { species.find(it) }
            candidate.copy(
                canonicalSpeciesId = canonical,
                commonName = found?.commonName?.takeIf { it.isNotBlank() },
            )
        }

    /** La especie de la ficha que hay que abrir para esta identificacion, o null si no esta en el catalogo. */
    suspend fun speciesIdOf(identification: ImageIdentification): String? {
        val candidate = identification.candidate
        return candidate.canonicalSpeciesId
            ?: resolver.resolve(identification.modelId, candidate.labelIndex)
            ?: candidate.speciesId
    }
}
