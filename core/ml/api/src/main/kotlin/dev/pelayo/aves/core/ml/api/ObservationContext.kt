package dev.pelayo.aves.core.ml.api

/**
 * Donde y cuando se hizo la observacion. Es lo que permite pasar de "6.522
 * especies posibles" a "las 300 que de verdad puede haber aqui en julio", que es
 * la mayor mejora de precision disponible sin tocar el modelo.
 *
 * Todo es opcional salvo el instante: el usuario puede haber denegado la
 * ubicacion, y en ese caso se clasifica sin prior en vez de no clasificar.
 */
data class ObservationContext(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val epochMillis: Long,
    /**
     * Cuanto pesa el contexto frente a la salida cruda del modelo, de 0 a 1.
     * Se expone como ajuste al usuario porque hay un caso de uso real en el que
     * debe valer 0: analizar una grabacion hecha por otra persona en otro sitio.
     */
    val contextInfluence: Float = 0.5f,
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null
}
