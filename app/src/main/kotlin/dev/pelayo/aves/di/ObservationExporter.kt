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
            // Con la marca de orden, Excel abre el fichero como UTF-8. Sin ella
            // lo lee en la codificacion del sistema y los acentos salen rotos.
            append(BOM)
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
                appendLine(fields.joinToString(SEPARATOR, transform = ::csvField))
            }
        }
    }

    private fun Float.format(): String = String.format(java.util.Locale.ROOT, "%.3f", this)

    companion object {
        const val SEPARATOR = ","
        const val MIME_TYPE = "text/csv"

        /** Marca de orden de bytes de UTF-8. */
        val BOM = Char(0xFEFF)

        /**
         * Un campo listo para el CSV.
         *
         * RFC 4180: comillas dobles alrededor si hace falta, y las internas
         * duplicadas. Ademas, lo que empieza por `=`, `+`, `@` o tabulador se
         * desactiva con un apostrofo delante: una hoja de calculo lo ejecutaria
         * como formula, y las notas las escribe cualquiera. El `-` se deja,
         * porque las longitudes al oeste de Greenwich empiezan asi.
         */
        fun csvField(field: String): String {
            val safe = if (field.isNotEmpty() && field[0] in FORMULA_TRIGGERS) "'$field" else field
            return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"" + safe.replace("\"", "\"\"") + "\""
            } else {
                safe
            }
        }

        private const val FORMULA_TRIGGERS = "=+@\t"

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
