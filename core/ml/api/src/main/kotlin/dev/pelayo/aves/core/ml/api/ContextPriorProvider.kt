package dev.pelayo.aves.core.ml.api

/**
 * Probabilidad a priori de encontrar cada especie en un lugar y momento dados.
 */
interface SpeciesPrior {
    /** Multiplicador en [0,1] para una especie canonica. */
    operator fun get(speciesId: String): Float

    companion object {
        /** Prior neutro: se usa cuando no hay ubicacion o el usuario lo desactiva. */
        val Uniform: SpeciesPrior = object : SpeciesPrior {
            override fun get(speciesId: String): Float = 1f
        }
    }
}

/**
 * Fuente del prior geo-temporal. Hay dos implementaciones previstas, con la
 * misma interfaz: el meta-modelo MData de BirdNET v2.4, el geomodelo de BirdNET+ y un
 * prior derivado de las ocurrencias de GBIF (el respaldo de los dos).
 *
 * Regla que no se debe romper: **ningun prior puede valer exactamente 0**.
 * Con un cero, una especie deja de ser detectable por construccion, y las
 * primeras victimas serian precisamente las rarezas, que son lo mas interesante
 * que puede pasar. Usa un suelo pequeno (~0.05) en su lugar.
 */
interface ContextPriorProvider {
    suspend fun prior(context: ObservationContext): SpeciesPrior
}
