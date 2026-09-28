package dev.pelayo.aves.core.ml.api

/**
 * Que modelo escucha los cantos. Lo elige el usuario en Ajustes.
 *
 * La app lleva los dos dentro: no se publica (BirdNET v2.4 es no comercial), y
 * asi se pueden comparar en el campo.
 */
enum class AudioModelChoice {
    /** BirdNET v2.4: ~6.400 especies. El de siempre. */
    BIRDNET,

    /** BirdNET+ V3: ~8.900 especies, mas nuevo. */
    BIRDNET_PLUS,

    /**
     * Los dos sobre cada ventana, y de cada especie la puntuacion mas alta. Es
     * lo que mas detecta; cuesta casi el doble de bateria.
     */
    BOTH,
    ;

    companion object {
        val DEFAULT = BIRDNET

        fun fromName(name: String?): AudioModelChoice = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Como se identifican las clases cuando escuchan los dos modelos a la vez.
 *
 * Cada modelo numera sus clases desde 0, y la especie se resuelve con el par
 * (modelo, indice). La escucha combinada tiene un solo modelId, asi que los
 * indices de BirdNET+ se desplazan [SECOND_OFFSET]: el mismo par sigue
 * diciendo de que modelo salio cada deteccion, sin tabla nueva en la base.
 */
object CombinedModel {
    const val MODEL_ID = "birdnet-v2.4+birdnetplus-v3.0"

    /** Deben coincidir con los de cada modelo y con los TSV del catalogo. */
    const val FIRST_MODEL_ID = "birdnet-v2.4"
    const val SECOND_MODEL_ID = "birdnetplus-v3.0"

    /** Muy por encima del numero de clases de cualquiera de los dos (~10.000). */
    const val SECOND_OFFSET = 1_000_000

    fun encodeFirst(labelIndex: Int): Int = labelIndex

    fun encodeSecond(labelIndex: Int): Int = SECOND_OFFSET + labelIndex

    /** El (modelId, indice) real de un indice combinado. */
    fun decode(labelIndex: Int): Pair<String, Int> =
        if (labelIndex >= SECOND_OFFSET) {
            SECOND_MODEL_ID to labelIndex - SECOND_OFFSET
        } else {
            FIRST_MODEL_ID to labelIndex
        }
}
