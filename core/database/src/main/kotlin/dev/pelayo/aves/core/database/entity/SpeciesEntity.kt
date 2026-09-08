package dev.pelayo.aves.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Catalogo canonico de especies: la taxonomia de eBird entera (~11.200
 * especies), no las etiquetas de un modelo concreto.
 *
 * [speciesId] es la clave interna del proyecto y NO viene de ninguna fuente
 * externa a proposito. Si se usara el codigo eBird como clave primaria, cambiar
 * de taxonomia obligaria a migrar todas las observaciones del usuario. Asi solo
 * hay que reescribir una columna de mapeo.
 *
 * El catalogo es mas grande que lo que cualquier modelo sabe identificar, y eso
 * es deliberado: cada modelo es una tabla de mapeo mas ([ModelLabelEntity]) y
 * anadir uno nuevo no obliga a regenerar el catalogo. Lo que el usuario "puede
 * conseguir" se calcula sobre los modelos enlazados, no sobre esta tabla.
 */
@Entity(
    tableName = "species",
    indices = [
        Index("scientificName", unique = true),
        Index("ebirdCode"),
        Index("family"),
        Index("searchName"),
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
    /**
     * Texto por el que se busca: nombre comun y cientifico en minusculas y sin
     * diacriticos. Existe porque SQLite no sabe que "carabo" y "cárabo" son la
     * misma palabra, y con el movil en una mano nadie pone tildes.
     */
    @ColumnInfo(defaultValue = "") val searchName: String = "",
    /** Resumen de la especie (Wikipedia), cacheado la primera vez que se abre la ficha. */
    val summary: String? = null,
    /** Atribucion de la imagen, que en Commons varia por fichero. */
    val imageAttribution: String? = null,
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
