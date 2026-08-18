package dev.pelayo.aves.core.ml.birdnet

/**
 * Una clase del modelo. El fichero de BirdNET usa el formato
 * `Nombre cientifico_Nombre comun`, una linea por clase y en el mismo orden que
 * el tensor de salida.
 */
data class BirdNetLabel(
    val scientificName: String,
    val commonName: String,
    /**
     * true para las 11 clases que no son ningun ser vivo: perro, motor,
     * ambiente, fuegos artificiales, disparo, ruido, sirena, herramientas
     * electricas y las tres de humano (vocal, no vocal y silbido).
     *
     * BirdNET las incluye a proposito para poder decir "esto es un perro" en vez
     * de forzar el ave mas parecida, pero no deben llegar a la interfaz.
     */
    val isNonEvent: Boolean,
    /**
     * true para los ~90 animales que NO son aves: ranas, sapos, grillos,
     * saltamontes, un ciervo y algun mono.
     *
     * Se detectan por su codigo taxonomico, que empieza por `t-` en vez de ser
     * un codigo eBird. Sin este filtro la coleccion de una app que se llama
     * "Aves" acabaria con una rana toro dentro.
     */
    val isNonBird: Boolean,
) {
    /** Solo las aves llegan a la interfaz y a la coleccion. */
    val isBird: Boolean get() = !isNonEvent && !isNonBird

    /**
     * Identificador provisional derivado del nombre cientifico.
     *
     * Coincide con el `speciesId` canonico que genera `build-catalog.ps1`, asi
     * que cuando la Fase 2 conecte la base de datos el cambio es transparente.
     * La tabla `model_labels` pasara a ser la fuente de verdad, porque resuelve
     * casos que esto no puede: los 38 taxones de BirdNET cuyo codigo eBird
     * caduco, y agrupaciones como Parus minor dentro de Parus major.
     */
    val provisionalSpeciesId: String
        get() = scientificName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
}

internal object BirdNetLabels {

    /**
     * Las 11 clases que no corresponden a ningun ser vivo, por su "nombre
     * cientifico". Comprobadas contra el fichero real de 6.522 etiquetas.
     *
     * Ojo con "Human vocal" y "Power tools": parecen binomios latinos y por eso
     * es facil contarlas de menos si se filtra con una expresion regular de
     * "Genero especie".
     */
    private val NON_EVENT_NAMES = setOf(
        "Dog",
        "Engine",
        "Environmental",
        "Fireworks",
        "Gun",
        "Human non-vocal",
        "Human vocal",
        "Human whistle",
        "Noise",
        "Power tools",
        "Siren",
    )

    /** Numero de clases del tensor de salida de BirdNET v2.4. */
    const val EXPECTED_COUNT = 6522

    /**
     * Aves de verdad entre esas clases: 6.522 menos 11 no-eventos y 90 animales
     * que no son aves. Se corresponden con 6.419 especies unicas del catalogo
     * (hay clases distintas que acaban en la misma especie tras las
     * agrupaciones taxonomicas de eBird).
     */
    const val BIRD_SPECIES_COUNT = 6419

    /**
     * Clases del tensor que son aves: 6.522 - 11 no-eventos - 90 no-aves.
     * Son mas que especies unicas porque varias clases colapsan en la misma
     * especie tras las agrupaciones taxonomicas de eBird.
     */
    const val BIRD_CLASS_COUNT = 6421

    /**
     * @param labelLines contenido de labels_es.txt
     * @param taxoCodeLines contenido de taxo_code.txt. Opcional: si no se
     *   proporciona, no se pueden distinguir los animales que no son aves.
     */
    fun parse(
        labelLines: Sequence<String>,
        taxoCodeLines: Sequence<String>? = null,
    ): List<BirdNetLabel> {
        val codes = taxoCodeLines
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toList()

        val labels = labelLines
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapIndexed { index, line ->
                // El separador es '_'. Se parte solo por el primero: algunos
                // nombres comunes lo contienen.
                val separator = line.indexOf('_')
                val scientific = if (separator >= 0) line.substring(0, separator) else line
                val common = if (separator >= 0) line.substring(separator + 1) else line
                val nonEvent = scientific in NON_EVENT_NAMES
                BirdNetLabel(
                    scientificName = scientific,
                    commonName = common,
                    isNonEvent = nonEvent,
                    // Los codigos de BirdNET para no-aves son identificadores
                    // internos `t-XXXXXXXX`; los de aves son codigos eBird.
                    isNonBird = !nonEvent && codes?.getOrNull(index)?.startsWith("t-") == true,
                )
            }
            .toList()

        // Si esto salta, el fichero de etiquetas no corresponde al modelo
        // cargado y TODAS las identificaciones estarian desplazadas: seria un
        // fallo silencioso y catastrofico. Mejor reventar aqui.
        check(labels.size == EXPECTED_COUNT) {
            "Se esperaban $EXPECTED_COUNT etiquetas y se leyeron ${labels.size}. " +
                "El fichero de etiquetas no coincide con el modelo."
        }
        check(codes == null || codes.size == EXPECTED_COUNT) {
            "taxo_code.txt tiene ${codes?.size} lineas y se esperaban $EXPECTED_COUNT. " +
                "No corresponde al mismo modelo que las etiquetas."
        }
        return labels
    }
}
