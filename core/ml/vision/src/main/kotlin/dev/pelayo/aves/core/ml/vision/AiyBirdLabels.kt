package dev.pelayo.aves.core.ml.vision

/** Una clase del clasificador de imagen. */
data class AiyLabel(
    val index: Int,
    val scientificName: String,
    /** true para la clase `background`, que no es un ave. */
    val isBackground: Boolean,
) {
    /**
     * Identificador provisional derivado del nombre cientifico, igual que en el
     * clasificador de audio. La tabla `model_labels` es la fuente de verdad y
     * resuelve lo que esto no puede: sinonimos y subespecies.
     */
    val provisionalSpeciesId: String
        get() = scientificName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
}

internal object AiyBirdLabels {

    /** 964 especies mas la clase `background`. */
    const val EXPECTED_COUNT = 965

    private const val BACKGROUND = "background"

    /**
     * Parsea el labelmap de Google AIY.
     *
     * FORMATO Y LA TRAMPA QUE ESCONDE
     * El CSV tiene cabecera `id,name` y **no esta ordenado por id**: la clase
     * `background` aparece en la primera fila con id 964. Hay que indexar por la
     * columna, nunca por la posicion de la linea.
     *
     * Esto no es teorico. El changelog del modelo dice que la version 3
     * "Fixes out-of-order logit to labelmap mapping": las versiones 1 y 2
     * traian ese desajuste y devolvian la especie equivocada en silencio.
     * Repetir el error aqui, al leer el fichero, tendria exactamente el mismo
     * efecto y seria igual de dificil de notar.
     *
     * @return lista indexada por posicion, donde el indice coincide con el del
     *   tensor de salida.
     */
    fun parse(lines: Sequence<String>): List<AiyLabel> {
        val byIndex = HashMap<Int, AiyLabel>()

        lines.drop(1)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { line ->
                val separator = line.indexOf(',')
                if (separator <= 0) return@forEach
                val index = line.substring(0, separator).trim().toIntOrNull() ?: return@forEach
                val name = line.substring(separator + 1).trim()
                byIndex[index] = AiyLabel(
                    index = index,
                    scientificName = name,
                    isBackground = name.equals(BACKGROUND, ignoreCase = true),
                )
            }

        // Si esto salta, el labelmap no corresponde al modelo cargado y TODAS
        // las identificaciones saldrian desplazadas. Mejor reventar al cargar.
        check(byIndex.size == EXPECTED_COUNT) {
            "Se esperaban $EXPECTED_COUNT clases en el labelmap y se leyeron ${byIndex.size}. " +
                "El fichero no corresponde al modelo."
        }

        return (0 until EXPECTED_COUNT).map { index ->
            checkNotNull(byIndex[index]) {
                "Falta la clase con id $index en el labelmap: hay un hueco en la numeracion."
            }
        }
    }
}
