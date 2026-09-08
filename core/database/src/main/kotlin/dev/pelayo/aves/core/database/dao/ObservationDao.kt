package dev.pelayo.aves.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.pelayo.aves.core.database.entity.ListeningSessionEntity
import dev.pelayo.aves.core.database.entity.ObservationCandidateEntity
import dev.pelayo.aves.core.database.entity.ObservationEntity
import kotlinx.coroutines.flow.Flow

/**
 * Una sesion con lo que produjo, para el historial.
 *
 * Los recuentos se calculan en la consulta y no en Kotlin porque el historial
 * lista decenas de sesiones y cada una tendria que cargar sus observaciones
 * solo para contarlas.
 */
data class SessionWithCounts(
    @Embedded val session: ListeningSessionEntity,
    /** Especies detectadas que el usuario no ha descartado. */
    val speciesCount: Int,
    val confirmedCount: Int,
    val pendingCount: Int,
)

@Dao
interface ListeningSessionDao {

    @Insert
    suspend fun insert(session: ListeningSessionEntity): Long

    @Update
    suspend fun update(session: ListeningSessionEntity)

    @Query("SELECT * FROM listening_sessions WHERE id = :id")
    suspend fun findById(id: Long): ListeningSessionEntity?

    @Query("SELECT * FROM listening_sessions ORDER BY startedAt DESC LIMIT :limit")
    fun recent(limit: Int = 50): Flow<List<ListeningSessionEntity>>

    @Query(
        """
        SELECT s.*,
          (SELECT COUNT(*) FROM observations o WHERE o.sessionId = s.id AND o.userRejected = 0) AS speciesCount,
          (SELECT COUNT(*) FROM observations o WHERE o.sessionId = s.id AND o.userConfirmed = 1) AS confirmedCount,
          (SELECT COUNT(*) FROM observations o WHERE o.sessionId = s.id AND o.userConfirmed = 0 AND o.userRejected = 0) AS pendingCount
        FROM listening_sessions s
        ORDER BY s.startedAt DESC
        LIMIT :limit
        """,
    )
    fun recentWithCounts(limit: Int = 200): Flow<List<SessionWithCounts>>

    @Query(
        """
        UPDATE listening_sessions
        SET endedAt = :endedAt, windowsProcessed = :processed, windowsSkipped = :skipped
        WHERE id = :id
        """,
    )
    suspend fun close(id: Long, endedAt: Long, processed: Int, skipped: Int)

    /** Sesiones que no registraron nada: no aportan y ensucian el historial. */
    @Query(
        """
        DELETE FROM listening_sessions
        WHERE endedAt IS NOT NULL
          AND id NOT IN (SELECT DISTINCT sessionId FROM observations WHERE sessionId IS NOT NULL)
        """,
    )
    suspend fun deleteEmptySessions(): Int

    /** Borra la sesion y, por la clave ajena en cascada, sus observaciones. */
    @Query("DELETE FROM listening_sessions WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ObservationDao {

    @Query("SELECT * FROM observations WHERE id = :id")
    suspend fun findById(id: Long): ObservationEntity?

    @Query("SELECT * FROM observations WHERE sessionId = :sessionId AND speciesId = :speciesId")
    suspend fun findInSession(sessionId: Long, speciesId: String): ObservationEntity?

    /** La coleccion propiamente dicha: solo lo que el usuario ha confirmado. */
    @Query(
        """
        SELECT * FROM observations
        WHERE userConfirmed = 1
        ORDER BY timestamp DESC
        """,
    )
    fun confirmed(): Flow<List<ObservationEntity>>

    /** La bandeja de revision: ni confirmado ni rechazado todavia. */
    @Query(
        """
        SELECT * FROM observations
        WHERE userConfirmed = 0 AND userRejected = 0
        ORDER BY timestamp DESC
        """,
    )
    fun pendingReview(): Flow<List<ObservationEntity>>

    /** Todo lo de una escucha, de mas a menos seguro. Para la ficha de sesion. */
    @Query("SELECT * FROM observations WHERE sessionId = :sessionId ORDER BY adjustedConfidence DESC")
    fun inSession(sessionId: Long): Flow<List<ObservationEntity>>

    /** Todas las filas, para exportar. Suspendida y no Flow: es una foto, no una vista. */
    @Query("SELECT * FROM observations ORDER BY timestamp DESC")
    suspend fun all(): List<ObservationEntity>

    @Query("SELECT COUNT(DISTINCT speciesId) FROM observations WHERE userConfirmed = 1")
    fun confirmedSpeciesCount(): Flow<Int>

    @Query("SELECT DISTINCT speciesId FROM observations WHERE userConfirmed = 1")
    fun confirmedSpeciesIds(): Flow<List<String>>

    @Insert
    suspend fun insert(observation: ObservationEntity): Long

    @Update
    suspend fun update(observation: ObservationEntity)

    @Query("DELETE FROM observations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE observations SET userConfirmed = 1, userRejected = 0 WHERE id = :id")
    suspend fun confirm(id: Long)

    @Query("UPDATE observations SET userRejected = 1, userConfirmed = 0 WHERE id = :id")
    suspend fun reject(id: Long)

    /**
     * Devuelve una observacion al estado "sin revisar".
     *
     * No existe solo para deshacer un toque accidental. Sin esto, rechazar es
     * irreversible, y cambiar de opinion una semana despues mirando la
     * coleccion es un caso perfectamente normal en observacion de aves: uno
     * aprende a distinguir cantos con el tiempo.
     */
    @Query("UPDATE observations SET userConfirmed = 0, userRejected = 0 WHERE id = :id")
    suspend fun clearReview(id: Long)

    @Query("UPDATE observations SET notes = :notes WHERE id = :id")
    suspend fun updateNotes(id: Long, notes: String?)

    @Query("UPDATE observations SET speciesId = :speciesId, userConfirmed = 1, userRejected = 0 WHERE id = :id")
    suspend fun reassignAndConfirm(id: Long, speciesId: String)

    /**
     * "No era esto, era lo segundo": confirma la observacion como otra especie.
     *
     * El indice unico (sesion, especie) complica el caso en el que la especie
     * correcta YA tiene fila en la misma sesion (el modelo la detecto tambien,
     * por su cuenta). Entonces no se puede renombrar esta: se confirma aquella
     * y se descarta esta, que es exactamente lo que el usuario quiso decir.
     *
     * @return id de la observacion que quedo confirmada.
     */
    @Transaction
    suspend fun confirmAs(id: Long, speciesId: String): Long {
        val current = findById(id) ?: return id
        val sessionId = current.sessionId
        val twin = if (sessionId != null) findInSession(sessionId, speciesId) else null
        if (twin != null && twin.id != id) {
            confirm(twin.id)
            reject(id)
            return twin.id
        }
        reassignAndConfirm(id, speciesId)
        return id
    }

    // --- Rareza -------------------------------------------------------------

    /** Observaciones cuya rareza quedo pendiente y que tienen con que calcularla. */
    @Query(
        """
        SELECT * FROM observations
        WHERE rarityTierAtCapture = :level AND latitude IS NOT NULL AND longitude IS NOT NULL
        """,
    )
    suspend fun withRarityLevel(level: Int): List<ObservationEntity>

    @Query("UPDATE observations SET rarityTierAtCapture = :level WHERE id = :id")
    suspend fun updateRarity(id: Long, level: Int)

    // --- Candidatos ---------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandidates(candidates: List<ObservationCandidateEntity>)

    @Query("DELETE FROM observation_candidates WHERE observationId = :observationId")
    suspend fun deleteCandidatesFor(observationId: Long)

    @Query("SELECT * FROM observation_candidates WHERE observationId = :observationId ORDER BY rank")
    suspend fun candidatesFor(observationId: Long): List<ObservationCandidateEntity>

    /** Los candidatos de varias observaciones de una vez, para la bandeja entera. */
    @Query("SELECT * FROM observation_candidates WHERE observationId IN (:observationIds) ORDER BY observationId, rank")
    suspend fun candidatesForAll(observationIds: List<Long>): List<ObservationCandidateEntity>

    /** Sustituye la lista de candidatos de una observacion por la nueva. */
    @Transaction
    suspend fun replaceCandidates(observationId: Long, candidates: List<ObservationCandidateEntity>) {
        deleteCandidatesFor(observationId)
        if (candidates.isNotEmpty()) insertCandidates(candidates)
    }

    /**
     * La regla de escritura de la Pokedex, en una sola operacion atomica.
     *
     * Si la especie ya se registro en esta sesion, solo se actualiza cuando la
     * nueva deteccion es MEJOR. Si no, se inserta. Asi una escucha larga produce
     * una fila por especie y no cientos, y ademas la que queda es la deteccion
     * mas fiable de toda la sesion, no la ultima por casualidad.
     *
     * @return id de la observacion, nueva o existente.
     */
    @Transaction
    suspend fun recordBestInSession(observation: ObservationEntity): Long {
        val sessionId = observation.sessionId
            ?: return insert(observation)

        val existing = findInSession(sessionId, observation.speciesId)
            ?: return insert(observation)

        if (observation.adjustedConfidence > existing.adjustedConfidence) {
            update(
                observation.copy(
                    id = existing.id,
                    // Las decisiones del usuario mandan sobre el modelo: si ya
                    // confirmo o rechazo esta especie, una deteccion mejor no
                    // debe deshacer su criterio.
                    userConfirmed = existing.userConfirmed,
                    userRejected = existing.userRejected,
                    notes = existing.notes,
                ),
            )
        }
        return existing.id
    }
}
