package dev.pelayo.aves.feature.pokedex

import dev.pelayo.aves.core.model.ObservationSource
import dev.pelayo.aves.core.model.RarityTier
import dev.pelayo.aves.core.model.Species
import dev.pelayo.aves.core.common.SearchText

/*
 * Modelo y logica de la coleccion, deliberadamente sin nada de Android ni de
 * Room.
 *
 * Vive separado del ViewModel porque agrupar, filtrar y contar es donde de
 * verdad se puede meter la pata, y aqui se puede probar en la JVM sin
 * emulador ni base de datos.
 */

/**
 * Un avistamiento tal y como lo necesita la interfaz.
 *
 * Es un tipo propio de la feature y no la `ObservationEntity` de Room a
 * proposito: asi la logica de coleccion y sus tests no arrastran el runtime de
 * Room, y la pantalla no depende de la forma de una tabla.
 */
data class Sighting(
    val id: Long,
    val speciesId: String,
    val timestamp: Long,
    val rarity: RarityTier,
    val source: ObservationSource,
    val confidence: Float,
    val mediaPath: String? = null,
)

/** Una especie ya capturada, con todas las veces que se ha registrado. */
data class CollectedSpecies(
    val species: Species,
    /** Ordenados del mas reciente al mas antiguo. */
    val sightings: List<Sighting>,
) {
    /**
     * El mejor tier alcanzado, no el ultimo.
     *
     * El tier va congelado en cada captura, asi que la misma especie puede
     * valer distinto segun donde y cuando se oyo. Si alguien pillo un mirlo en
     * Islandia, la coleccion tiene que seguir ensenandolo como notable aunque
     * luego lo haya vuelto a oir cien veces en su jardin.
     */
    val rarity: RarityTier
        get() = sightings.maxByOrNull { it.rarity.level }?.rarity ?: RarityTier.VERY_COMMON

    val lastSeenAt: Long
        get() = sightings.firstOrNull()?.timestamp ?: 0L

    val count: Int get() = sightings.size
}

/**
 * Un bloque de la rejilla.
 *
 * Se agrupa por familia porque es como piensa un observador de aves: nadie
 * busca "el pajaro marron", busca un parido o un turdido. Ademas convierte la
 * lista en un mapa mental de que ramas del arbol tienes cubiertas.
 */
data class FamilyGroup(
    val family: String,
    val members: List<CollectedSpecies>,
)

/** Cuanto llevas del catalogo. */
data class CollectionProgress(
    val collected: Int,
    val total: Int,
) {
    val remaining: Int get() = (total - collected).coerceAtLeast(0)

    val fraction: Float
        get() = if (total <= 0) 0f else (collected.toFloat() / total).coerceIn(0f, 1f)

    /** Ej. "37 de 6.419 especies". */
    val label: String
        get() = "${formatThousands(collected)} de ${formatThousands(total)} especies"
}

/**
 * Junta avistamientos y catalogo en las tarjetas de la coleccion.
 *
 * Los avistamientos cuya especie no esta en el catalogo se descartan en vez de
 * pintarse a medias: un modelo puede predecir etiquetas que el catalogo local
 * todavia no conoce, y una tarjeta sin nombre no es informacion, es ruido.
 */
internal fun buildCollection(
    sightings: List<Sighting>,
    catalog: Map<String, Species>,
): List<CollectedSpecies> = sightings
    .groupBy { it.speciesId }
    .mapNotNull { (speciesId, rows) ->
        val species = catalog[speciesId] ?: return@mapNotNull null
        CollectedSpecies(
            species = species,
            sightings = rows.sortedByDescending { it.timestamp },
        )
    }

/**
 * Agrupa por familia y ordena alfabeticamente dentro de cada una.
 *
 * El orden alfabetico por nombre comun es el de las guias de campo: predecible
 * y buscable con la vista. Ordenar por rareza seria mas vistoso pero moveria
 * las tarjetas de sitio cada vez que cambia un tier.
 */
internal fun groupByFamily(entries: List<CollectedSpecies>): List<FamilyGroup> = entries
    .groupBy { it.species.family }
    .map { (family, members) ->
        FamilyGroup(
            family = family,
            members = members.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.species.commonName },
            ),
        )
    }
    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.family })

/**
 * Filtro de texto sobre la coleccion.
 *
 * Busca tambien por nombre cientifico y por familia porque escribir "Parus" o
 * "Turdidae" es una consulta legitima aqui, no una rareza.
 */
internal fun filterCollection(
    entries: List<CollectedSpecies>,
    query: String,
): List<CollectedSpecies> {
    val needle = foldForSearch(query)
    if (needle.isEmpty()) return entries
    return entries.filter { entry ->
        val species = entry.species
        foldForSearch(species.commonName).contains(needle) ||
            foldForSearch(species.scientificName).contains(needle) ||
            foldForSearch(species.family).contains(needle) ||
            foldForSearch(species.genus).contains(needle)
    }
}

/**
 * Normaliza para comparar: minusculas y sin diacriticos.
 *
 * Sin esto, buscar "carabo" no encontraria "Carabo comun", que es exactamente
 * lo que va a escribir alguien con el movil en una mano y unos prismaticos en
 * la otra.
 */
internal fun foldForSearch(value: String): String = SearchText.fold(value)

/**
 * Separador de millares a la espanola, sin depender del Locale del dispositivo.
 *
 * Se hace a mano porque el numero forma parte de un texto ya en espanol: si el
 * telefono esta en ingles, `NumberFormat` daria "6,419" dentro de una frase
 * castellana.
 */
/**
 * Porcentaje con un decimal y coma, como se escribe en español.
 *
 * Vive aqui y no en el composable por coherencia con [formatThousands]: el
 * separador de miles ya se hacia a mano para no depender del Locale del
 * dispositivo, pero el decimal se dejaba a `String.format`, que SI depende de
 * el. El resultado era "6.419" bien y "0.0 %" mal en la misma linea.
 *
 * Toda la interfaz esta en español fijo, asi que se formatea en español fijo.
 */
internal fun formatPercent(fraction: Float): String {
    val tenths = kotlin.math.round(fraction.coerceIn(0f, 1f) * 1000f).toInt()
    return "${tenths / 10},${tenths % 10}"
}

internal fun formatThousands(value: Int): String {
    val digits = value.coerceAtLeast(0).toString()
    if (digits.length <= 3) return digits

    return buildString(digits.length + digits.length / 3) {
        digits.forEachIndexed { index, char ->
            if (index > 0 && (digits.length - index) % 3 == 0) append('.')
            append(char)
        }
    }
}

/** Nombre visible del tier. */
internal fun rarityLabel(tier: RarityTier): String = when (tier) {
    RarityTier.VERY_COMMON -> "Muy común"
    RarityTier.COMMON -> "Común"
    RarityTier.UNCOMMON -> "Poco común"
    RarityTier.RARE -> "Rara"
    RarityTier.EXCEPTIONAL -> "Excepcional"
    RarityTier.UNKNOWN -> "Sin datos"
}
