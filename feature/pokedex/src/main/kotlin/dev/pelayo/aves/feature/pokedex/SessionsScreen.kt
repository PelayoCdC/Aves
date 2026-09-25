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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pelayo.aves.core.common.runSuspendCatching
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
import kotlinx.coroutines.flow.flow
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
        viewModelScope.launch { runSuspendCatching { deleteSession(sessionId) } }
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
            text = stringResource(R.string.pokedex_sessions_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state.sessions.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.pokedex_sessions_empty),
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
            title = { Text(stringResource(R.string.pokedex_sessions_delete_title)) },
            text = { Text(stringResource(R.string.pokedex_sessions_delete_body, row.speciesCount)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(row.session.id)
                        deleting = null
                    },
                ) { Text(stringResource(R.string.pokedex_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.pokedex_cancel)) }
            },
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
                    text = buildList {
                        add(durationText(session))
                        add(pluralStringResource(R.plurals.pokedex_species_count, row.speciesCount, row.speciesCount.toString()))
                        if (row.confirmedCount > 0) add(stringResource(R.string.pokedex_session_confirmed, row.confirmedCount))
                        if (row.pendingCount > 0) add(stringResource(R.string.pokedex_session_pending, row.pendingCount))
                        if (session.cellId == null) add(stringResource(R.string.pokedex_session_no_location))
                    }.joinToString(SEPARATOR),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.pokedex_sessions_delete))
            }
        }
    }
}

private const val SEPARATOR = " · "

/** "12 min", "1 h 05 min" o "en curso". */
@Composable
internal fun durationText(session: ListeningSessionEntity): String {
    val ended = session.endedAt ?: return stringResource(R.string.pokedex_duration_in_progress)
    val minutes = ((ended - session.startedAt) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> stringResource(R.string.pokedex_duration_under_minute)
        minutes < 60 -> stringResource(R.string.pokedex_duration_minutes, minutes)
        else -> stringResource(
            R.string.pokedex_duration_hours,
            minutes / 60,
            (minutes % 60).toString().padStart(2, '0'),
        )
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
    val commonName: String?,
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

    private val session = flow { emit(observationRepository.session(sessionId)) }

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
                        commonName = species?.commonName,
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
            text = session?.let { formatSightingDate(it.startedAt) } ?: stringResource(R.string.pokedex_session_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        session?.let {
            Text(
                text = buildList {
                    add(durationText(it))
                    add(stringResource(R.string.pokedex_session_windows, it.windowsProcessed))
                    if (it.windowsSkipped > 0) add(stringResource(R.string.pokedex_session_silent, it.windowsSkipped))
                    add(stringResource(R.string.pokedex_session_model, it.modelId))
                }.joinToString(SEPARATOR),
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
                            Text(
                                text = row.commonName ?: stringResource(R.string.pokedex_detail_not_in_catalog),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = row.scientificName,
                                style = ScientificNameStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(
                                    R.string.pokedex_session_row,
                                    (row.confidence * 100).toInt(),
                                    statusText(row.status),
                                ),
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
                                    contentDescription = stringResource(
                                        if (canPlay) R.string.pokedex_session_play else R.string.pokedex_session_clip_missing,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusText(status: ReviewStatus): String = stringResource(
    when (status) {
        ReviewStatus.CONFIRMED -> R.string.pokedex_status_confirmed
        ReviewStatus.PENDING -> R.string.pokedex_status_pending
        ReviewStatus.REJECTED -> R.string.pokedex_status_rejected
    },
)
