package dev.pelayo.aves.core.ml.birdnetplus

/**
 * Una clase del modelo BirdNET+ V3.
 *
 * A diferencia de BirdNET v2.4, aqui el fichero de etiquetas trae una columna
 * `class` con la clase taxonomica, asi que distinguir las aves del resto es
 * directo. En la v2.4 habia que deducirlo del prefijo del codigo taxonomico, un
 * apaño que solo funcionaba por casualidad.
 */
data class BirdNetPlusLabel(
    val index: Int,
    val scientificName: String,
    val commonName: String,
    /** "Aves", "Amphibia", "Mammalia", "Insecta". */
    val taxonClass: String,
    val order: String,
) {
    /** El modelo incluye ranas, grillos y mamiferos a proposito. Esta app no. */
    val isBird: Boolean get() = taxonClass.equals(AVES, ignoreCase = true)

    /**
     * Identificador canonico, derivado del nombre cientifico igual que en el
     * resto del proyecto. Coincide con el que genera `build-catalog.ps1`.
     */
    val speciesId: String
        get() = scientificName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private companion object {
        const val AVES = "Aves"
    }
}

internal object BirdNetPlusLabels {

    /** Taxones del modelo podado a 10K. */
    const val EXPECTED_COUNT = 9789

    /** De esos, los que son aves. El resto son anfibios, mamiferos e insectos. */
    const val BIRD_COUNT = 8923

    private const val DELIMITER = ';'

    /**
     * Parsea el CSV de etiquetas: `idx;id;sci_name;com_name;class;order`.
     *
     * Se indexa por la columna `idx` y no por la posicion de la linea. Aunque
     * este fichero SI viene ordenado, confiar en eso es exactamente el error que
     * la version 3 del clasificador de imagen tuvo que corregir en su dia, y no
     * cuesta nada no cometerlo.
     */
    fun parse(lines: Sequence<String>): List<BirdNetPlusLabel> {
        val byIndex = HashMap<Int, BirdNetPlusLabel>()

        lines.drop(1)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { line ->
                val columns = line.split(DELIMITER)
                if (columns.size < 6) return@forEach
                val index = columns[0].trim().toIntOrNull() ?: return@forEach
                byIndex[index] = BirdNetPlusLabel(
                    index = index,
                    scientificName = columns[2].trim(),
                    commonName = columns[3].trim(),
                    taxonClass = columns[4].trim(),
                    order = columns[5].trim(),
                )
            }

        // Un desajuste aqui desplazaria TODAS las identificaciones y el modelo
        // seguiria funcionando, devolviendo la especie equivocada con toda
        // confianza. Mejor reventar al cargar.
        check(byIndex.size == EXPECTED_COUNT) {
            "Se esperaban $EXPECTED_COUNT etiquetas y se leyeron ${byIndex.size}. " +
                "El fichero no corresponde al modelo."
        }

        return (0 until EXPECTED_COUNT).map { index ->
            checkNotNull(byIndex[index]) {
                "Falta la clase con indice $index: hay un hueco en la numeracion."
            }
        }
    }
}
