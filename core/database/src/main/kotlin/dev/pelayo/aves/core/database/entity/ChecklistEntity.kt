package dev.pelayo.aves.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * La lista de aves de una zona administrativa: un pais, una region o una
 * provincia.
 *
 * Es otra cosa que [RegionCellEntity]. Aquella es una celda de 40 km alrededor
 * de donde estas escuchando, por meses, y sirve para la rareza y el prior. Esta
 * es "las aves de Asturias", todo el ano, y sirve para que la Pokedex pueda
 * decir que te falta por ver AQUI en vez de ensenar 6.000 huecos del planeta.
 *
 * @param regionId el codigo ISO del pais ("ES") o el identificador GADM de la
 *   region o provincia ("ESP.3_1", "ESP.3.1_1").
 */
@Entity(tableName = "region_checklists")
data class RegionChecklistEntity(
    @PrimaryKey val regionId: String,
    val name: String,
    val fetchedAt: Long,
    /** Registros de aves en la zona: el denominador de la cuota de cada especie. */
    val totalOccurrences: Long,
)

/** Una especie de la lista de una zona, con lo frecuente que es alli. */
@Entity(
    tableName = "region_checklist_species",
    primaryKeys = ["regionId", "speciesId"],
    indices = [Index("speciesId")],
)
data class RegionChecklistSpeciesEntity(
    val regionId: String,
    val speciesId: String,
    val occurrenceCount: Long,
    /** occurrenceCount / totalOccurrences de la zona. */
    val share: Float,
)

/**
 * Como quiere ver el usuario su Pokedex, y cual es su zona.
 *
 * Una sola fila (id = 0). Vive en Room y no en DataStore porque la Pokedex ya
 * habla con esta base de datos y no con la pantalla de ajustes, y porque la
 * zona detectada tiene que sobrevivir sin red: se detecta una vez y se guarda
 * con sus tres niveles.
 *
 * No hay cuentas de usuario en esta app. "Tu zona" es donde estabas la primera
 * vez que se abrio la Pokedex con ubicacion, o el pais del idioma del movil si
 * no la habia, y se puede volver a detectar cuando se quiera.
 */
@Entity(tableName = "pokedex_preferences")
data class PokedexPreferencesEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** Nombre de un `ZoneLevel`. Texto y no ordinal: el orden del enum puede cambiar. */
    val zoneLevel: String,
    val showMissing: Boolean,
    val countryCode: String? = null,
    val countryName: String? = null,
    val regionId: String? = null,
    val regionName: String? = null,
    val provinceId: String? = null,
    val provinceName: String? = null,
    /** Cuando se detecto la zona. 0 si nunca. */
    val detectedAt: Long = 0L,
    /**
     * Una zona elegida a mano en lugar de la detectada ("quiero ver Extremadura
     * aunque viva en Madrid"). Mientras tenga valor, manda sobre `zoneLevel`.
     */
    val customZoneId: String? = null,
    val customZoneName: String? = null,
    /** Nombre de un `ZoneLevel`, como `zoneLevel`. */
    val customZoneLevel: String? = null,
    /** Un poligono WKT, para las zonas que no son administrativas (Doñana...). */
    val customZoneGeometry: String? = null,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
