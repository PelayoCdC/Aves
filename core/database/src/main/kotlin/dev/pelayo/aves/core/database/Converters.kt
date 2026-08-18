package dev.pelayo.aves.core.database

import androidx.room.TypeConverter
import dev.pelayo.aves.core.model.ObservationSource

/**
 * Los enums se guardan por NOMBRE y no por ordinal a proposito. Guardar el
 * ordinal ata el esquema al orden de declaracion: insertar un valor nuevo en
 * medio del enum corromperia silenciosamente todas las filas existentes.
 */
class Converters {

    @TypeConverter
    fun observationSourceToString(source: ObservationSource): String = source.name

    @TypeConverter
    fun stringToObservationSource(value: String): ObservationSource =
        runCatching { ObservationSource.valueOf(value) }.getOrDefault(ObservationSource.MANUAL)
}
