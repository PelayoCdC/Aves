package dev.pelayo.aves.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.pelayo.aves.core.database.entity.ListeningSessionEntity
import dev.pelayo.aves.core.database.entity.ObservationCandidateEntity
import dev.pelayo.aves.core.database.entity.ObservationEntity
import kotlinx.coroutines.flow.Flow

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

    @Query("SELECT COUNT(DISTINCT speciesId) FROM observations WHERE userConfirmed = 1")
    fun confirmedSpeciesCount(): Flow<Int>

    @Query("SELECT DISTINCT speciesId FROM observations WHERE userConfirmed = 1")
    fun confirmedSpeciesIds(): Flow<List<String>>

    @Insert
    suspend fun insert(observation: ObservationEntity): Long

    @Update
    suspend fun update(observation: ObservationEntity)

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandidates(candidates: List<ObservationCandidateEntity>)

    @Query("SELECT * FROM observation_candidates WHERE observationId = :observationId ORDER BY rank")
    suspend fun candidatesFor(observationId: Long): List<ObservationCandidateEntity>

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
