package dev.pelayo.aves.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.pelayo.aves.core.model.ObservationSource

/**
 * Un avistamiento: la unidad de la Pokedex.
 *
 * Regla de escritura: **una fila por (especie, sesion)**, con la mejor
 * puntuacion alcanzada. El indice unico de abajo la hace cumplir en la propia
 * base de datos y no solo en el repositorio, porque una regla que solo vive en
 * codigo se salta tarde o temprano.
 *
 * Se guardan DOS confianzas a proposito: [rawConfidence] es lo que dijo el
 * modelo y [adjustedConfidence] lo que quedo tras el prior geo-temporal.
 * Separarlas permite auditar despues si el filtro de contexto ayuda o estorba,
 * cosa imposible si solo se guarda el numero final.
 *
 * [userConfirmed] es la frontera entre "la maquina cree" y "yo lo vi". Sin ese
 * filtro humano la coleccion no significa nada para un observador de aves.
 */
@Entity(
    tableName = "observations",
    foreignKeys = [
        ForeignKey(
            entity = ListeningSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("speciesId"),
        Index("timestamp"),
        Index("userConfirmed"),
        Index(value = ["sessionId", "speciesId"], unique = true),
    ],
)
data class ObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val speciesId: String,
    /** Nulo para observaciones manuales, que no pertenecen a ninguna escucha. */
    val sessionId: Long? = null,
    val timestamp: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val source: ObservationSource,
    val rawConfidence: Float,
    val adjustedConfidence: Float,
    /** Que modelo lo dijo. Trazabilidad al cambiar de clasificador. */
    val modelId: String,
    /** Recorte de audio o foto. Codificado, no WAV: ver politica de retencion. */
    val mediaPath: String? = null,
    val userConfirmed: Boolean = false,
    /** true cuando el usuario lo ha rechazado explicitamente en la revision. */
    val userRejected: Boolean = false,
    val notes: String? = null,
    /**
     * Tier de rareza congelado en el momento de la captura.
     *
     * No se recalcula nunca. La rareza depende de (especie, lugar, mes), asi que
     * si el usuario se muda, sus capturas antiguas no deben cambiar de valor
     * retroactivamente: aquel mirlo en Islandia fue notable de verdad.
     */
    val rarityTierAtCapture: Int,
)

/**
 * Los candidatos top-K de cada observacion.
 *
 * Se guardan para poder auditar el modelo y, sobre todo, para que la bandeja de
 * revision pueda ofrecer "no era esto, era lo segundo" en vez de obligar a
 * buscar la especie correcta a mano.
 */
@Entity(
    tableName = "observation_candidates",
    foreignKeys = [
        ForeignKey(
            entity = ObservationEntity::class,
            parentColumns = ["id"],
            childColumns = ["observationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("observationId")],
)
data class ObservationCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val observationId: Long,
    val speciesId: String,
    val rank: Int,
    val score: Float,
)
