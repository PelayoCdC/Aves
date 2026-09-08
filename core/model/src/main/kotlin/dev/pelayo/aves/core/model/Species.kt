package dev.pelayo.aves.core.model

/**
 * Una especie del catalogo canonico.
 *
 * [speciesId] es la clave interna del proyecto, deliberadamente independiente de
 * la taxonomia de cualquier modelo o API concretos. Los identificadores externos
 * ([ebirdCode], [gbifSpeciesKey]) son opcionales porque no toda especie que
 * predice un modelo tiene equivalente en las tres fuentes.
 *
 * Los campos de enciclopedia ([imageUrl], [wikipediaUrl], [summary],
 * [imageAttribution]) llegan vacios del catalogo y se rellenan la primera vez
 * que alguien abre la ficha.
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
    val summary: String? = null,
    val imageAttribution: String? = null,
) {
    /** Ya se consulto la enciclopedia, con o sin exito: no hay que volver a pedirla. */
    val hasEncyclopedia: Boolean get() = summary != null || imageUrl != null || wikipediaUrl != null
}
