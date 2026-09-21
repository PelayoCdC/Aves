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
 * catalogo tiene `gbifSpeciesKey` a null en todas las especies: poblarlo
 * exigiria miles de llamadas de emparejamiento taxonomico. Facetando por nombre
 * el cruce es directo y gratis, porque el `speciesId` YA se deriva del nombre
 * cientifico.
 *
 * La contrapartida esta asumida: los nombres cambian con las revisiones
 * taxonomicas, asi que algunas especies no cruzaran. El efecto es que esas
 * caen al suelo del prior y se tratan como raras, que es el fallo seguro:
 * peor seria darlas por comunes y ocultar una rareza de verdad. Los cambios de
 * genero mas sonados se cubren con [ALIASES].
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

        val id = slug("$genus $epithet")
        return ALIASES[id] ?: id
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

    /**
     * Nombre que usa (o ha usado) GBIF -> nombre del catalogo.
     *
     * El catalogo sigue a eBird/Clements, que entre 2022 y 2024 cambio de
     * genero a un punado de aves muy comunes; el backbone de GBIF no siempre va
     * al mismo paso. Sin esto, la garcilla bueyera o la curruca zarcera
     * desaparecian de las listas por zona y salian como "excepcionales" en la
     * rareza. Es seguro aplicarlo siempre: los ids de la izquierda NO existen
     * en el catalogo, asi que no pisan a nadie.
     *
     * Es la misma lista de excepciones auditadas de `build-catalog.ps1`.
     */
    private val ALIASES: Map<String, String> = mapOf(
        "bubulcus_ibis" to "ardea_ibis",
        "charadrius_mongolus" to "anarhynchus_mongolus",
        "charadrius_montanus" to "anarhynchus_montanus",
        "charadrius_wilsonia" to "anarhynchus_wilsonia",
        "charadrius_nivosus" to "anarhynchus_nivosus",
        "charadrius_alexandrinus" to "anarhynchus_alexandrinus",
        "charadrius_dubius" to "thinornis_dubius",
        "phaeomyias_murina" to "nesotriccus_murinus",
        "psilorhinus_morio" to "cyanocorax_morio",
        "calocitta_formosa" to "cyanocorax_formosus",
        "calocitta_colliei" to "cyanocorax_colliei",
        "euphonia_elegantissima" to "chlorophonia_elegantissima",
        "phalacrocorax_pelagicus" to "urile_pelagicus",
        "phalacrocorax_penicillatus" to "urile_penicillatus",
        "phalacrocorax_auritus" to "nannopterum_auritum",
        "phalacrocorax_brasilianus" to "nannopterum_brasilianum",
        "sylvia_communis" to "curruca_communis",
        "sylvia_melanocephala" to "curruca_melanocephala",
        "sylvia_undata" to "curruca_undata",
        "sylvia_cantillans" to "curruca_cantillans",
        "sylvia_hortensis" to "curruca_hortensis",
        "sylvia_conspicillata" to "curruca_conspicillata",
        "sylvia_curruca" to "curruca_curruca",
        "regulus_calendula" to "corthylio_calendula",
        "ixobrychus_exilis" to "botaurus_exilis",
        "ixobrychus_minutus" to "botaurus_minutus",
        "milvago_chimachima" to "daptrius_chimachima",
        "accipiter_gentilis" to "astur_gentilis",
        "accipiter_cooperii" to "astur_cooperii",
        "haliaeetus_vocifer" to "icthyophaga_vocifer",
        "haliaeetus_leucogaster" to "icthyophaga_leucogaster",
        "amazilia_violiceps" to "ramosomyia_violiceps",
        "lonchura_oryzivora" to "padda_oryzivora",
        "corvus_caurinus" to "corvus_brachyrhynchos",
        "empidonax_occidentalis" to "empidonax_difficilis",
    )

    /** Para poder comprobar en un test que ningun alias apunta a un id inexistente. */
    internal val aliasTargets: Collection<String> get() = ALIASES.values
}
