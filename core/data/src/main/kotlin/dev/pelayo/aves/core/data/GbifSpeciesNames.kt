package dev.pelayo.aves.core.data

/**
 * Traduce los nombres cientificos que devuelve GBIF a nuestro `speciesId`.
 *
 * GBIF devuelve el nombre con la autoria detras: `"Turdus merula Linnaeus, 1758"`
 * o `"Erithacus rubecula (Linnaeus, 1758)"`. Nuestro `speciesId` es el binomio
 * en minusculas con guiones bajos, asi que basta quedarse con las dos primeras
 * palabras.
 *
 * POR QUE POR NOMBRE Y NO POR CLAVE
 * GBIF permite facetar por `speciesKey`, que seria lo natural. Pero nuestro
 * catalogo tiene `gbifSpeciesKey` a null en las 6.419 especies: poblarlo
 * exigiria miles de llamadas de emparejamiento taxonomico. Facetando por nombre
 * el cruce es directo y gratis, porque el `speciesId` YA se deriva del nombre
 * cientifico.
 *
 * La contrapartida esta asumida: los nombres cambian con las revisiones
 * taxonomicas, asi que algunas especies no cruzaran. El efecto es que esas
 * caen al suelo del prior y se tratan como raras, que es el fallo seguro:
 * peor seria darlas por comunes y ocultar una rareza de verdad.
 */
object GbifSpeciesNames {

    /**
     * @return el `speciesId` canonico, o null si el nombre no es un binomio
     *   utilizable (un genero suelto, un hibrido, una entrada malformada).
     */
    fun toSpeciesId(gbifScientificName: String): String? {
        val tokens = gbifScientificName.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.size < 2) return null

        val genus = tokens[0]
        val epithet = tokens[1]

        // Los hibridos vienen como "Anas platyrhynchos x Anas acuta" o con el
        // simbolo de multiplicacion, y el marcador puede ir al principio o en
        // medio. Buscarlo solo al principio dejaba pasar el caso infijo como si
        // fuera la primera de las dos especies. Un hibrido no es ninguna de las
        // dos y no debe sumar a ninguna.
        if (tokens.any { it == "x" || it == HYBRID_MARKER }) return null
        if (genus.startsWith(HYBRID_MARKER)) return null

        // Un genero valido empieza por mayuscula; el epiteto va en minuscula.
        // Si el segundo token empieza por mayuscula, es autoria y no epiteto:
        // el nombre venia sin especie ("Turdus Linnaeus, 1758").
        if (!genus.first().isUpperCase()) return null
        if (epithet.first().isUpperCase() || epithet.startsWith("(")) return null

        // Marcadores de identificacion incierta: "Turdus sp.", "Turdus cf. merula".
        if (epithet.trimEnd('.') in UNCERTAIN_MARKERS) return null

        return slug("$genus $epithet")
    }

    /**
     * Misma normalizacion que aplica `scripts/build-catalog.ps1` al generar el
     * catalogo. Si las dos divergen, el cruce con GBIF deja de funcionar en
     * silencio, asi que conviene que este en un unico sitio del lado Kotlin.
     */
    fun slug(scientificName: String): String =
        scientificName.lowercase().replace(NON_ALPHANUMERIC, "_").trim('_')

    private val WHITESPACE = Regex("\\s+")
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
    private const val HYBRID_MARKER = "×"
    private val UNCERTAIN_MARKERS = setOf("sp", "spp", "cf", "aff")
}
