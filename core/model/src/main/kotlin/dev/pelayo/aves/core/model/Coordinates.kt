package dev.pelayo.aves.core.model

/** Una posicion. Existe para no arrastrar android.location fuera de :app. */
data class Coordinates(
    val latitude: Double,
    val longitude: Double,
)
