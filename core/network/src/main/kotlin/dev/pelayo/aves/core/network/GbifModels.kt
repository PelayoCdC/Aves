package dev.pelayo.aves.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Clave taxonomica de la clase Aves en el backbone de GBIF. */
const val GBIF_TAXON_KEY_AVES: Int = 212

// Nombres de campo tal y como GBIF los devuelve dentro de `facets`. Ojo: el
// parametro de consulta va en camelCase (facet=scientificName) pero la respuesta
// los rotula en mayusculas con guion bajo. No son intercambiables.
internal const val FACET_FIELD_SCIENTIFIC_NAME = "SCIENTIFIC_NAME"
internal const val FACET_FIELD_SPECIES_KEY = "SPECIES_KEY"
internal const val FACET_FIELD_MONTH = "MONTH"

/**
 * Configuracion de deserializacion para todo lo que venga de GBIF.
 *
 * `ignoreUnknownKeys` no es opcional aqui: una respuesta de /occurrence/search
 * trae decenas de campos (offset, endOfRecords, results, diagnostics, facetas de
 * campos que no se han pedido...) y GBIF anade mas sin previo aviso. Reventar el
 * parseo por un campo nuevo cuando solo se leen `count` y `facets` seria absurdo.
 *
 * `coerceInputValues` cubre el otro extremo: si un campo esperado llega como
 * null, se usa el valor por defecto en vez de lanzar. La rareza es un adorno de
 * la ficha, no puede tumbar la pantalla.
 */
internal val GbifJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * Respuesta de `GET /v1/occurrence/search`.
 *
 * Se consulta siempre con `limit=0`: no interesan los registros individuales,
 * solo el recuento total y las facetas. Por eso `results` ni se declara.
 */
@Serializable
data class GbifOccurrenceResponse(
    /** Ocurrencias totales que cumplen el filtro, independientemente de la especie. */
    val count: Long = 0,
    val facets: List<GbifFacet> = emptyList(),
)

/** Un bloque de faceta: el campo agrupado y sus recuentos. */
@Serializable
data class GbifFacet(
    @SerialName("field") val field: String = "",
    val counts: List<GbifFacetCount> = emptyList(),
)

/**
 * Un valor de faceta con su recuento.
 *
 * `name` es texto incluso cuando el valor es numerico (speciesKey, mes): es asi
 * en el JSON de GBIF, y forzarlo a Int en el modelo haria que un valor raro
 * rompiese la respuesta entera en vez de descartarse.
 */
@Serializable
data class GbifFacetCount(
    val name: String = "",
    val count: Long = 0,
)

/**
 * Recuento de ocurrencias en una celda espacio-temporal, agrupado por nombre
 * cientifico.
 *
 * Es lo que da sentido a la rareza en esta app: no es propiedad de la especie,
 * sino del trio (especie, lugar, mes). El mismo taxon puede ser trivial en la
 * celda de al lado.
 *
 * Un resumen vacio significa "GBIF no tiene registros aqui", que es informacion
 * real. Un fallo de red **no** produce un resumen vacio: lanza
 * [GbifUnavailableException]. Ver la nota de [GbifClient].
 */
data class GbifOccurrenceSummary(
    /** Ocurrencias totales de la celda; sirve de denominador para la rareza relativa. */
    val total: Long,
    /**
     * Nombre cientifico **crudo** de GBIF -> ocurrencias.
     *
     * Crudo quiere decir con la autoria incluida ("Turdus merula Linnaeus, 1758",
     * "Erithacus rubecula (Linnaeus, 1758)"). Normalizarlo es trabajo de
     * :core:data, que es quien conoce el formato de nuestros speciesId; aqui
     * recortarlo solo serviria para perder informacion de forma irreversible.
     *
     * Conserva el orden que devuelve GBIF (descendente por recuento), que ya es
     * el util para cortar por las N especies mas comunes.
     */
    val speciesCounts: Map<String, Long>,
) {
    val isEmpty: Boolean get() = total == 0L && speciesCounts.isEmpty()

    companion object {
        val EMPTY = GbifOccurrenceSummary(total = 0, speciesCounts = emptyMap())
    }
}

/**
 * Igual que [GbifOccurrenceSummary] pero agrupado por speciesKey.
 *
 * Se mantiene aparte porque la clave numerica es inequivoca y no depende de la
 * autoria ni de sinonimos. Hoy el catalogo no tiene poblado `gbifSpeciesKey`,
 * asi que la via principal es por nombre, pero para identificar **una** especie
 * concreta (perfil fenologico, resolucion de sinonimos) la clave es mas fiable.
 */
data class GbifSpeciesKeySummary(
    val total: Long,
    val speciesCounts: Map<Int, Long>,
) {
    val isEmpty: Boolean get() = total == 0L && speciesCounts.isEmpty()

    companion object {
        val EMPTY = GbifSpeciesKeySummary(total = 0, speciesCounts = emptyMap())
    }
}

/** Recuentos de una faceta, con la clave textual tal cual llega. */
internal fun GbifOccurrenceResponse.countsByName(field: String): Map<String, Long> =
    facetCounts(field)
        ?.associate { it.name to it.count }
        .orEmpty()

/**
 * Recuentos de una faceta cuya clave es numerica (speciesKey, mes).
 *
 * Los valores que no son numeros se descartan en silencio en lugar de propagar
 * un error: perder una entrada anomala es mucho menos grave que quedarse sin
 * rareza por culpa de un valor mal formado.
 */
internal fun GbifOccurrenceResponse.countsByIntKey(field: String): Map<Int, Long> =
    facetCounts(field)
        ?.mapNotNull { entry -> entry.name.toIntOrNull()?.let { key -> key to entry.count } }
        ?.toMap()
        .orEmpty()

// Se busca la faceta por nombre en vez de coger la primera: si algun dia la
// consulta pide dos facetas, quedarse con la de la posicion 0 seria una bomba.
private fun GbifOccurrenceResponse.facetCounts(field: String): List<GbifFacetCount>? =
    facets.firstOrNull { it.field.equals(field, ignoreCase = true) }?.counts

internal fun GbifOccurrenceResponse.toNameSummary(): GbifOccurrenceSummary =
    GbifOccurrenceSummary(total = count, speciesCounts = countsByName(FACET_FIELD_SCIENTIFIC_NAME))

internal fun GbifOccurrenceResponse.toSpeciesKeySummary(): GbifSpeciesKeySummary =
    GbifSpeciesKeySummary(total = count, speciesCounts = countsByIntKey(FACET_FIELD_SPECIES_KEY))
