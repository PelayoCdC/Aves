package dev.pelayo.aves.di

import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.SpeciesRepository
import dev.pelayo.aves.core.database.entity.ObservationEntity
import dev.pelayo.aves.core.model.RarityTier
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Exporta la coleccion a CSV.
 *
 * Es la contrapartida de "tu coleccion vive solo en este movil": si vive solo
 * aqui, tiene que poder salir de aqui en un formato que abra cualquier hoja de
 * calculo y que eBird o cualquier otra plataforma pueda importar con un poco de
 * masaje. Una columna por dato, una fila por observacion, fechas ISO 8601 con
 * zona. Nada mas.
 *
 * Se exportan TODAS las observaciones, tambien las pendientes y las
 * rechazadas, con su estado en una columna: es el registro completo, y quien
 * lo abra decide que filtrar.
 */
class ObservationExporter(
    private val observations: ObservationRepository,
    private val species: SpeciesRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun toCsv(): String {
        val rows = observations.all()
        val catalog = species.findAll(rows.map(ObservationEntity::speciesId).distinct())
            .associateBy { it.speciesId }

        return buildString {
            appendLine(HEADER.joinToString(SEPARATOR))
            for (row in rows) {
                val found = catalog[row.speciesId]
                val fields = listOf(
                    ISO_FORMAT.format(Instant.ofEpochMilli(row.timestamp).atZone(zoneId)),
                    found?.commonName.orEmpty(),
                    found?.scientificName ?: row.speciesId,
                    found?.family.orEmpty(),
                    found?.order.orEmpty(),
                    row.speciesId,
                    found?.ebirdCode.orEmpty(),
                    row.source.name.lowercase(),
                    when {
                        row.userConfirmed -> "confirmada"
                        row.userRejected -> "rechazada"
                        else -> "pendiente"
                    },
                    row.adjustedConfidence.format(),
                    row.rawConfidence.format(),
                    RarityTier.fromLevel(row.rarityTierAtCapture).name.lowercase(),
                    row.latitude?.toString().orEmpty(),
                    row.longitude?.toString().orEmpty(),
                    row.sessionId?.toString().orEmpty(),
                    row.modelId,
                    row.notes.orEmpty(),
                )
                appendLine(fields.joinToString(SEPARATOR, transform = ::quote))
            }
        }
    }

    private fun Float.format(): String = String.format(java.util.Locale.ROOT, "%.3f", this)

    /** RFC 4180: comillas dobles alrededor si hace falta, y las internas duplicadas. */
    private fun quote(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }

    companion object {
        const val SEPARATOR = ","
        const val MIME_TYPE = "text/csv"

        val HEADER = listOf(
            "fecha", "nombre_comun", "nombre_cientifico", "familia", "orden", "species_id",
            "codigo_ebird", "fuente", "estado", "confianza", "confianza_modelo", "rareza",
            "latitud", "longitud", "sesion", "modelo", "notas",
        )

        private val ISO_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        fun suggestedFileName(nowMillis: Long): String =
            "aves-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(
                Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()),
            ) + ".csv"
    }
}
