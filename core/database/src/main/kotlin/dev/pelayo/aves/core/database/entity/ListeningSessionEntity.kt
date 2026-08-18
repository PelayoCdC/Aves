package dev.pelayo.aves.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Una sesion de escucha: desde que se pulsa el boton hasta que se para.
 *
 * Existe para resolver un problema muy concreto de la coleccion. Sin sesiones,
 * la unidad de registro seria la deteccion, y veinte minutos en el jardin
 * generarian cientos de filas del mismo mirlo. Con sesiones la regla es
 * **una observacion por especie y sesion**, con su mejor puntuacion, que es lo
 * que un observador de aves consideraria "un avistamiento".
 *
 * Tambien es la unidad natural para el usuario: "la escucha del sabado por la
 * manana" significa algo; "la deteccion numero 347" no.
 */
@Entity(
    tableName = "listening_sessions",
    indices = [Index("startedAt")],
)
data class ListeningSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    /** Nulo mientras la sesion sigue abierta. */
    val endedAt: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Celda geohash de precision 4, para agrupar y para el prior de contexto. */
    val cellId: String? = null,
    val windowsProcessed: Int = 0,
    /** Ventanas descartadas por el gate de energia. Mide cuanto ahorra. */
    val windowsSkipped: Int = 0,
    val modelId: String,
)
