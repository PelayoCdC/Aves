package dev.pelayo.aves.feature.pokedex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.SpeciesRepository
import dev.pelayo.aves.core.database.dao.SessionWithCounts
import dev.pelayo.aves.core.database.entity.ListeningSessionEntity
import dev.pelayo.aves.core.database.entity.ObservationEntity
import dev.pelayo.aves.core.designsystem.theme.ScientificNameStyle
import dev.pelayo.aves.core.media.ClipPlayIcon
import dev.pelayo.aves.core.media.rememberClipPlayer
import dev.pelayo.aves.core.model.Species
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/*
 * El historial de escuchas.
 *
 * "La salida del sabado por la manana" es la unidad natural para quien sale al
 * campo; "la deteccion numero 347" no. La tabla de sesiones se diseno con esa
 * intencion y tardo mucho en tener pantalla.
 */

data class SessionsUiState(
    val isLoading: Boolean = true,
    val sessions: List<SessionWithCounts> = emptyList(),
)

/**
 * @param deleteSession borra la sesion y sus recortes. Lo hace :app, que es
 *   quien sabe donde viven los ficheros.
 */
class SessionsViewModel(
    private val observationRepository: ObservationRepository,
    private val deleteSession: suspend (sessionId: Long) -> Unit,
) : ViewModel() {

    val uiState: StateFlow<SessionsUiState> = observationRepository.sessions()
        .map { SessionsUiState(isLoading = false, sessions = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SessionsUiState())

    fun delete(sessionId: Long) {
        viewModelScope.launch { runCatching { deleteSession(sessionId) } }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

@Composable
fun SessionsScreen(
    viewModelFactory: () -> SessionsViewModel,
    onSessionClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SessionsViewModel = viewModel { viewModelFactory() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<SessionWithCounts?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "Historial de escuchas",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state.sessions.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Aquí aparecerá cada escucha que haya detectado algo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.sessions, key = { it.session.id }) { row ->
                    SessionCard(
                        row = row,
                        onClick = { onSessionClick(row.session.id) },
                        onDelete = { deleting = row },
                    )
                }
            }
        }
    }

    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("¿Borrar esta escucha?") },
            text = {
                Text(
                    "Se borran sus ${row.speciesCount} detecciones y sus grabaciones, " +
                        "incluidas las que ya habías confirmado. No se puede deshacer.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(row.session.id)
                        deleting = null
                    },
                ) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun SessionCard(
    row: SessionWithCounts,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = row.session
    Card(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = formatSightingDate(session.startedAt),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = buildString {
                        append(durationLabel(session))
                        append(" · ")
                        append(if (row.speciesCount == 1) "1 especie" else "${row.speciesCount} especies")
                        if (row.confirmedCount > 0) append(" · ${row.confirmedCount} confirmadas")
                        if (row.pendingCount > 0) append(" · ${row.pendingCount} por revisar")
                        if (session.cellId == null) append(" · sin ubicación")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Borrar la escucha")
            }
        }
    }
}

/** "12 min", "1 h 05 min" o "en curso". */
internal fun durationLabel(session: ListeningSessionEntity): String {
    val ended = session.endedAt ?: return "en curso"
    val minutes = ((ended - session.startedAt) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "menos de un minuto"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} min"
    }
}

// --- Detalle de una sesion ---------------------------------------------------

data class SessionDetailUiState(
    val isLoading: Boolean = true,
    val session: ListeningSessionEntity? = null,
    val rows: List<SessionObservation> = emptyList(),
)

/** Una deteccion de la sesion con su nombre y su estado de revision. */
data class SessionObservation(
    val id: Long,
    val speciesId: String,
    val commonName: String,
    val scientificName: String,
    val confidence: Float,
    val mediaPath: String?,
    val status: ReviewStatus,
)

enum class ReviewStatus { CONFIRMED, PENDING, REJECTED }

class SessionDetailViewModel(
    private val sessionId: Long,
    private val observationRepository: ObservationRepository,
    private val speciesRepository: SpeciesRepository,
) : ViewModel() {

    private val session = kotlinx.coroutines.flow.flow { emit(observationRepository.session(sessionId)) }

    val uiState: StateFlow<SessionDetailUiState> = combine(
        session,
        observationRepository.observationsInSession(sessionId),
    ) { found, observations -> found to observations }
        .map { (found, observations) ->
            val catalog = speciesRepository.findAll(observations.map(ObservationEntity::speciesId).distinct())
                .associateBy(Species::speciesId)
            SessionDetailUiState(
                isLoading = false,
                session = found,
                rows = observations.map { observation ->
                    val species = catalog[observation.speciesId]
                    SessionObservation(
                        id = observation.id,
                        speciesId = observation.speciesId,
                        commonName = species?.commonName ?: "Especie sin catalogar",
                        scientificName = species?.scientificName ?: observation.speciesId,
                        confidence = observation.adjustedConfidence,
                        mediaPath = observation.mediaPath,
                        status = when {
                            observation.userConfirmed -> ReviewStatus.CONFIRMED
                            observation.userRejected -> ReviewStatus.REJECTED
                            else -> ReviewStatus.PENDING
                        },
                    )
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), SessionDetailUiState())
}

@Composable
fun SessionDetailScreen(
    sessionId: Long,
    viewModelFactory: (Long) -> SessionDetailViewModel,
    onSpeciesClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SessionDetailViewModel = viewModel(key = "sesion-$sessionId") { viewModelFactory(sessionId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clipPlayer = rememberClipPlayer()

    Column(modifier = modifier.fillMaxSize()) {
        val session = state.session
        Text(
            text = session?.let { formatSightingDate(it.startedAt) } ?: "Escucha",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        session?.let {
            Text(
                text = buildString {
                    append(durationLabel(it))
                    append(" · ${it.windowsProcessed} ventanas analizadas")
                    if (it.windowsSkipped > 0) append(", ${it.windowsSkipped} en silencio")
                    append(" · modelo ${it.modelId}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Spacer(Modifier.size(8.dp))

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.rows, key = { it.id }) { row ->
                val canPlay = remember(clipPlayer, row.mediaPath) { clipPlayer.canPlay(row.mediaPath) }
                Card(modifier = Modifier.fillMaxWidth().clickable { onSpeciesClick(row.speciesId) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(text = row.commonName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = row.scientificName,
                                style = ScientificNameStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "${(row.confidence * 100).toInt()} % · ${statusLabel(row.status)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = when (row.status) {
                                    ReviewStatus.CONFIRMED -> MaterialTheme.colorScheme.primary
                                    ReviewStatus.PENDING -> MaterialTheme.colorScheme.tertiary
                                    ReviewStatus.REJECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        if (row.mediaPath != null) {
                            IconButton(
                                onClick = { clipPlayer.toggle(row.id, row.mediaPath) },
                                enabled = canPlay,
                            ) {
                                ClipPlayIcon(
                                    isPlaying = clipPlayer.isPlaying(row.id),
                                    contentDescription = if (canPlay) "Reproducir" else "Sin grabación",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun statusLabel(status: ReviewStatus): String = when (status) {
    ReviewStatus.CONFIRMED -> "confirmada"
    ReviewStatus.PENDING -> "por revisar"
    ReviewStatus.REJECTED -> "descartada"
}
