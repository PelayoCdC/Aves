package dev.pelayo.aves.feature.pokedex

import dev.pelayo.aves.core.common.SearchText
import dev.pelayo.aves.core.data.ZoneSpecies
import dev.pelayo.aves.core.model.ObservationSource
import dev.pelayo.aves.core.model.RarityTier
import dev.pelayo.aves.core.model.Species
import java.time.Instant
import java.time.ZoneId

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
    val notes: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/**
 * De que maneras se ha conseguido una especie: vista, fotografiada, oida.
 *
 * Son tres logros distintos y asi se ensenan. Haber oido un carabo no es haberlo
 * visto, y quien colecciona quiere saber cual de las tres casillas le falta. Se
 * cuentan (y no solo se marcan) porque la ficha ensena cuantas hay de cada una.
 */
data class CaptureSources(
    /** Avistamientos anotados a mano: "lo he visto". */
    val seen: Int = 0,
    /** Identificaciones por foto, de la camara o de la galeria. */
    val photographed: Int = 0,
    /** Detecciones por canto confirmadas. */
    val heard: Int = 0,
) {
    fun count(source: ObservationSource): Int = when (source) {
        ObservationSource.MANUAL -> seen
        ObservationSource.IMAGE -> photographed
        ObservationSource.AUDIO -> heard
    }

    fun has(source: ObservationSource): Boolean = count(source) > 0

    companion object {
        /** Ninguna: lo que tiene una especie que aun no esta en la coleccion. */
        val None = CaptureSources()

        fun of(sightings: List<Sighting>): CaptureSources = CaptureSources(
            seen = sightings.count { it.source == ObservationSource.MANUAL },
            photographed = sightings.count { it.source == ObservationSource.IMAGE },
            heard = sightings.count { it.source == ObservationSource.AUDIO },
        )
    }
}

/** Una especie ya capturada, con todas las veces que se ha registrado. */
data class CollectedSpecies(
    val species: Species,
    /** Ordenados del mas reciente al mas antiguo. */
    val sightings: List<Sighting>,
) {
    /** Se calcula una vez: la rejilla lo pide en cada tarjeta y en cada recomposicion. */
    val sources: CaptureSources by lazy { CaptureSources.of(sightings) }

    /**
     * El mejor tier alcanzado, no el ultimo.
     *
     * El tier va congelado en cada captura, asi que la misma especie puede
     * valer distinto segun donde y cuando se oyo. Si alguien pillo un mirlo en
     * Islandia, la coleccion tiene que seguir ensenandolo como notable aunque
     * luego lo haya vuelto a oir cien veces en su jardin. Lo desconocido no
     * compite: si todos son desconocidos, se dice.
     */
    val rarity: RarityTier
        get() = sightings.maxByOrNull { it.rarity.level }?.rarity ?: RarityTier.UNKNOWN

    val lastSeenAt: Long
        get() = sightings.firstOrNull()?.timestamp ?: 0L

    /** Cuando entro en la coleccion: la primera vez que se vio. */
    val firstSeenAt: Long
        get() = sightings.lastOrNull()?.timestamp ?: 0L

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
    /** Especies de la familia que hay en la zona y aun no se han visto. */
    val missing: List<MissingSpecies> = emptyList(),
) {
    val size: Int get() = members.size + missing.size
}

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
 * Los numeros que dan ganas de seguir: cuantas familias, cuantas este mes,
 * cual fue la ultima "primera".
 *
 * Son estadisticas de coleccionista, no de ciencia. Existen porque una lista
 * de lo que ya tienes no invita a salir otra vez; "3 especies nuevas este mes"
 * si.
 */
data class CollectionStats(
    val species: Int,
    val families: Int,
    /** Especies vistas por primera vez en el mes de referencia. */
    val newThisMonth: Int,
    /** La ultima especie que entro en la coleccion, o null si esta vacia. */
    val latestLifer: CollectedSpecies?,
    /** Cuantas de la coleccion son raras o excepcionales. */
    val notable: Int,
)

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
 * Las familias van en orden taxonomico ([familyOrder]), que es el de las guias
 * de campo; dentro de cada familia, alfabetico por nombre comun: predecible y
 * buscable con la vista. Ordenar por rareza seria mas vistoso pero moveria las
 * tarjetas de sitio cada vez que cambia un tier.
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
    .sortedWith(familyOrder(entries.map { it.species }) { it.family })

/**
 * Orden de las familias: el taxonomico de eBird (la primera especie de cada
 * familia marca su sitio), que es el de cualquier guia de campo. Antes era el
 * alfabetico del nombre latino, que ponia a los Accipitridae junto a los
 * Acrocephalidae. Lo que no trae orden taxonomico (0) va al final, por nombre.
 */
internal fun <T> familyOrder(species: List<Species>, familyOf: (T) -> String): Comparator<T> {
    val firstInFamily = HashMap<String, Int>()
    for (entry in species) {
        val order = entry.taxonOrder.takeIf { it > 0 } ?: continue
        val current = firstInFamily[entry.family]
        if (current == null || order < current) firstInFamily[entry.family] = order
    }
    return compareBy<T> { firstInFamily[familyOf(it)] ?: Int.MAX_VALUE }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { familyOf(it) }
}

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
 * Estadisticas de la coleccion en el instante [nowMillis].
 *
 * "Este mes" es el mes natural del calendario del dispositivo, no los ultimos
 * 30 dias: es como la gente cuenta ("en abril vi tres nuevas").
 */
internal fun computeStats(
    entries: List<CollectedSpecies>,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): CollectionStats {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    val newThisMonth = entries.count { entry ->
        val first = Instant.ofEpochMilli(entry.firstSeenAt).atZone(zoneId)
        first.year == now.year && first.month == now.month
    }
    return CollectionStats(
        species = entries.size,
        families = entries.map { it.species.family }.distinct().size,
        newThisMonth = newThisMonth,
        latestLifer = entries.maxByOrNull { it.firstSeenAt },
        notable = entries.count { it.rarity.level >= RarityTier.RARE.level },
    )
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

/**
 * Separador de millares a la espanola, sin depender del Locale del dispositivo.
 *
 * Se hace a mano porque el numero forma parte de un texto ya en espanol: si el
 * telefono esta en ingles, `NumberFormat` daria "6,419" dentro de una frase
 * castellana.
 */
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

// --- Filtro por zona -------------------------------------------------------

/**
 * Una especie de la zona que todavia no esta en la coleccion.
 *
 * @param share cuota de los registros de aves de la zona, o null si no se esta
 *   filtrando por ninguna. Es lo que permite decir "esta es facil, es comun
 *   aqui" en vez de ensenar trescientos huecos iguales.
 */
data class MissingSpecies(
    val species: Species,
    val share: Float?,
) {
    /** Lo rara que es EN LA ZONA, no en general. Null sin zona. */
    val rarityHere: RarityTier? get() = share?.let(RarityTier::fromShare)
}

/** Lo que se pinta: familias con lo que tienes y, si se pide, lo que te falta. */
data class ZoneView(
    val groups: List<FamilyGroup>,
    val progress: CollectionProgress,
    /**
     * Especies de la coleccion que el filtro deja fuera por no ser de la zona.
     * Se cuenta para poder decirlo: si no, el ave que se trajo de un viaje
     * "desaparece" y parece un fallo.
     */
    val hiddenOutsideZone: Int,
)

/**
 * Cruza la coleccion con la lista de una zona.
 *
 * @param zoneSpecies las aves de la zona, o null si no hay lista con la que
 *   filtrar (el mundo sin "ver las que faltan", o una zona cuya lista aun no se
 *   ha podido descargar). Con null NO se filtra: es preferible ensenar la
 *   coleccion entera a ensenar una vacia por no tener red.
 * @param fallbackTotal el total cuando no hay lista: lo que los modelos
 *   enlazados saben identificar.
 */
internal fun buildZoneView(
    collected: List<CollectedSpecies>,
    zoneSpecies: List<ZoneSpecies>?,
    showMissing: Boolean,
    query: String,
    fallbackTotal: Int,
): ZoneView {
    if (zoneSpecies == null) {
        return ZoneView(
            groups = groupByFamily(filterCollection(collected, query)),
            progress = CollectionProgress(collected = collected.size, total = fallbackTotal),
            hiddenOutsideZone = 0,
        )
    }

    val zoneIds = zoneSpecies.mapTo(HashSet(zoneSpecies.size)) { it.species.speciesId }
    val inZone = collected.filter { it.species.speciesId in zoneIds }
    val collectedIds = collected.mapTo(HashSet(collected.size)) { it.species.speciesId }

    val missing = if (showMissing) {
        filterMissing(
            zoneSpecies
                .filter { it.species.speciesId !in collectedIds }
                .map { MissingSpecies(it.species, it.share) },
            query,
        )
    } else {
        emptyList()
    }

    return ZoneView(
        groups = groupByFamily(filterCollection(inZone, query), missing),
        progress = CollectionProgress(collected = inZone.size, total = zoneSpecies.size),
        hiddenOutsideZone = collected.size - inZone.size,
    )
}

/** Igual que [groupByFamily], intercalando en cada familia lo que falta por ver. */
internal fun groupByFamily(
    entries: List<CollectedSpecies>,
    missing: List<MissingSpecies>,
): List<FamilyGroup> {
    if (missing.isEmpty()) return groupByFamily(entries)

    val collectedByFamily = entries.groupBy { it.species.family }
    val missingByFamily = missing.groupBy { it.species.family }
    val byName = compareBy<Species, String>(String.CASE_INSENSITIVE_ORDER) { it.commonName }

    val allSpecies = entries.map { it.species } + missing.map { it.species }
    return (collectedByFamily.keys + missingByFamily.keys)
        .sortedWith(familyOrder<String>(allSpecies) { it })
        .map { family ->
            FamilyGroup(
                family = family,
                members = collectedByFamily[family].orEmpty().sortedWith(compareBy(byName) { it.species }),
                missing = missingByFamily[family].orEmpty().sortedWith(compareBy(byName) { it.species }),
            )
        }
}

internal fun filterMissing(entries: List<MissingSpecies>, query: String): List<MissingSpecies> {
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

/** Como se ordena la coleccion. */
enum class CollectionSort {
    /** Por familias, en el orden de las guias. Es el de siempre, y el unico con "las que faltan". */
    TAXONOMIC,

    /** Lo ultimo que viste, primero. */
    RECENT,

    /** Lo mas raro que tienes, primero; lo de rareza desconocida, al final. */
    RARITY,
}

/** Que capturas cuentan para ensenar una especie: todas, o solo las de un origen. */
enum class SourceFilter(val source: ObservationSource?) {
    ALL(null),
    SEEN(ObservationSource.MANUAL),
    PHOTOGRAPHED(ObservationSource.IMAGE),
    HEARD(ObservationSource.AUDIO),
}

/**
 * Nombre del bloque unico cuando la coleccion no va por familias. Vacio a
 * proposito: la cabecera la pone la pantalla, que es quien sabe los textos.
 */
internal const val FLAT_GROUP = ""

/**
 * Aplica el orden y el filtro de origen a los bloques ya agrupados por familia.
 *
 * Con un orden que no es el taxonomico, las familias dejan de tener sentido y
 * todo va en un solo bloque ([FLAT_GROUP]); "las que faltan" solo se ensenan
 * por familias, que es donde dicen algo. El filtro de origen deja las especies
 * con al menos una captura de ese tipo ("que he fotografiado").
 */
internal fun arrange(
    groups: List<FamilyGroup>,
    sort: CollectionSort,
    filter: SourceFilter,
): List<FamilyGroup> {
    val wanted = filter.source
    val filtered = if (wanted == null) {
        groups
    } else {
        groups.mapNotNull { group ->
            val members = group.members.filter { entry -> entry.sightings.any { it.source == wanted } }
            // Con un filtro de origen, lo que falta no se ha capturado de ninguna forma: fuera.
            if (members.isEmpty()) null else group.copy(members = members, missing = emptyList())
        }
    }
    if (sort == CollectionSort.TAXONOMIC) return filtered

    val all = filtered.flatMap { it.members }
    val sorted = when (sort) {
        CollectionSort.RECENT -> all.sortedByDescending { it.lastSeenAt }
        CollectionSort.RARITY -> all.sortedWith(
            compareByDescending<CollectedSpecies> { it.rarity.level }.thenByDescending { it.lastSeenAt },
        )
        CollectionSort.TAXONOMIC -> all
    }
    return if (sorted.isEmpty()) emptyList() else listOf(FamilyGroup(family = FLAT_GROUP, members = sorted))
}
