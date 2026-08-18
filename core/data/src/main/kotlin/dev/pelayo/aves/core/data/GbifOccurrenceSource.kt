package dev.pelayo.aves.core.data

import dev.pelayo.aves.core.network.GbifClient

/**
 * Adaptador entre el cliente de GBIF y el puerto que consume [RegionRepository].
 *
 * Existe esta capa fina para que el repositorio, que es donde vive la logica de
 * rareza, se pueda probar con un doble sin red ni Ktor. Tambien deja la puerta
 * abierta a otra fuente de ocurrencias sin tocar el calculo de tiers.
 *
 * Ojo con lo que NO hace: no traga las excepciones del cliente. Un fallo de red
 * debe llegar arriba como fallo, no convertirse en un resumen vacio, porque
 * cachear "no hay aves aqui" por haber tenido mala cobertura un momento dejaria
 * toda la zona con las especies marcadas como excepcionales.
 */
class GbifOccurrenceSource(private val client: GbifClient) : OccurrenceSource {

    override suspend fun occurrencesInCell(
        latitude: Double,
        longitude: Double,
        radiusKm: Int,
        month: Int,
    ): OccurrenceSummary {
        val summary = client.occurrencesInCell(
            latitude = latitude,
            longitude = longitude,
            radiusKm = radiusKm,
            month = month,
        )
        return OccurrenceSummary(
            total = summary.total,
            byScientificName = summary.speciesCounts,
        )
    }
}
