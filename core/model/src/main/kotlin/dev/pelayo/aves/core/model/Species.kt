package dev.pelayo.aves.core.model

/**
 * Una especie del catalogo canonico.
 *
 * [speciesId] es la clave interna del proyecto, deliberadamente independiente de
 * la taxonomia de cualquier modelo o API concretos. Los identificadores externos
 * ([ebirdCode], [gbifSpeciesKey]) son opcionales porque no toda especie que
 * predice un modelo tiene equivalente en las tres fuentes.
 */
data class Species(
    val speciesId: String,
    val scientificName: String,
    val genus: String,
    val family: String,
    val order: String,
    val commonName: String,
    val ebirdCode: String? = null,
    val gbifSpeciesKey: Int? = null,
    val imageUrl: String? = null,
    val wikipediaUrl: String? = null,
)
