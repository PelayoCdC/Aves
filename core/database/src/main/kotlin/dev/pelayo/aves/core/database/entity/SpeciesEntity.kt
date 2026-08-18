package dev.pelayo.aves.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Catalogo canonico de especies.
 *
 * [speciesId] es la clave interna del proyecto y NO viene de ninguna fuente
 * externa a proposito. Si se usara el codigo eBird como clave primaria, cambiar
 * de taxonomia obligaria a migrar todas las observaciones del usuario. Asi solo
 * hay que reescribir una columna de mapeo.
 */
@Entity(
    tableName = "species",
    indices = [
        Index("scientificName", unique = true),
        Index("ebirdCode"),
        Index("family"),
    ],
)
data class SpeciesEntity(
    @PrimaryKey val speciesId: String,
    val scientificName: String,
    val genus: String,
    val family: String,
    val order: String,
    /** Nombre comun en el idioma por defecto del catalogo (español). */
    val defaultCommonName: String,
    val ebirdCode: String? = null,
    val gbifSpeciesKey: Int? = null,
    val imageUrl: String? = null,
    val wikipediaUrl: String? = null,
)

/**
 * Nombres comunes por idioma. Tabla aparte y no columnas porque el numero de
 * idiomas crece y BirdNET publica etiquetas en mas de treinta.
 */
@Entity(
    tableName = "species_names",
    primaryKeys = ["speciesId", "languageTag"],
    indices = [Index("commonName")],
)
data class SpeciesNameEntity(
    val speciesId: String,
    /** Etiqueta BCP-47: "es", "en", "en-GB". */
    val languageTag: String,
    val commonName: String,
)
