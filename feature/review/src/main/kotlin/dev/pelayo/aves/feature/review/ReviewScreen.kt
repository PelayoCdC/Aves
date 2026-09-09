package dev.pelayo.aves.feature.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pelayo.aves.core.designsystem.theme.AvesTheme
import dev.pelayo.aves.core.media.rememberClipPlayer

/**
 * Bandeja de revision.
 *
 * @param viewModelFactory lo construye quien tiene los repositorios. Mismo
 *   patron que `IdentifyScreen` con el clasificador.
 */
@Composable
fun ReviewScreen(
    viewModelFactory: () -> ReviewViewModel,
    modifier: Modifier = Modifier,
) {
    val viewModel: ReviewViewModel = viewModel { viewModelFactory() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ReviewContent(
        state = state,
        onConfirm = viewModel::confirm,
        onReject = viewModel::reject,
        onConfirmAs = viewModel::confirmAs,
        onUndo = viewModel::undo,
        modifier = modifier,
    )
}

/**
 * La pantalla sin ViewModel, para poder previsualizarla con datos a mano.
 */
@Composable
internal fun ReviewContent(
    state: ReviewUiState,
    onConfirm: (Long) -> Unit,
    onReject: (Long) -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
    onConfirmAs: (Long, Alternative) -> Unit = { _, _ -> },
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val undoable = state.undoable
    val context = LocalContext.current

    // Uno para toda la pantalla, no uno por tarjeta: asi solo puede sonar un
    // recorte a la vez y solo hay un reproductor que liberar al salir.
    val clipPlayer = rememberClipPlayer()

    // El aviso se dispara al cambiar la accion reversible: si el usuario despacha
    // dos tarjetas seguidas, esta corrutina se reinicia y el segundo aviso
    // sustituye al primero en vez de encolarse detras.
    LaunchedEffect(undoable) {
        if (undoable == null) return@LaunchedEffect
        val name = undoable.observation.commonName
        val message = when (undoable.decision) {
            ReviewDecision.CONFIRMED -> context.getString(
                if (undoable.observation.isLifer) R.string.review_added_lifer else R.string.review_added,
                name,
            )

            ReviewDecision.REJECTED -> context.getString(R.string.review_rejected, name)
            ReviewDecision.REASSIGNED ->
                context.getString(R.string.review_reassigned, undoable.reassignTo?.commonName ?: name)
        }
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = context.getString(R.string.review_undo),
            withDismissAction = false,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onUndo()
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

            state.pending.isEmpty() -> AllCaughtUp(Modifier.align(Alignment.Center))

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Header(count = state.pending.size)
                }
                items(state.pending, key = { it.observationId }) { observation ->
                    // Comprobar si el recorte existe toca disco, y la lista se
                    // recompone con cada decision: se resuelve una vez por
                    // tarjeta en lugar de en cada recomposicion.
                    val canPlay = remember(clipPlayer, observation.mediaPath) {
                        clipPlayer.canPlay(observation.mediaPath)
                    }

                    ReviewCard(
                        observation = observation,
                        // La tarjeta desaparece de la lista al decidir. Si seguia
                        // sonando, el canto se quedaria sin nada en pantalla que
                        // lo pudiera parar.
                        onConfirm = {
                            clipPlayer.stopIfPlaying(observation.observationId)
                            onConfirm(observation.observationId)
                        },
                        onReject = {
                            clipPlayer.stopIfPlaying(observation.observationId)
                            onReject(observation.observationId)
                        },
                        onConfirmAs = { alternative ->
                            clipPlayer.stopIfPlaying(observation.observationId)
                            onConfirmAs(observation.observationId, alternative)
                        },
                        canPlayClip = canPlay,
                        isPlaying = clipPlayer.isPlaying(observation.observationId),
                        onTogglePlay = {
                            clipPlayer.toggle(observation.observationId, observation.mediaPath)
                        },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}

@Composable
private fun Header(count: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.review_header),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            // El recuento importa: dice cuanto queda, y revisar es una cola de
            // trabajo, no un muro infinito.
            text = pluralStringResource(R.plurals.review_pending_count, count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Estado vacio.
 *
 * Llegar aqui es un logro, no una carencia: significa que la coleccion entera ha
 * pasado por las manos del usuario. El texto lo celebra en vez de limitarse a
 * informar de que no hay filas.
 */
@Composable
private fun AllCaughtUp(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.review_all_caught_up_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.review_all_caught_up_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(16.dp))
        Text(
            text = stringResource(R.string.review_all_caught_up_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ReviewScreenPreview() {
    AvesTheme(dynamicColor = false) {
        ReviewContent(
            state = ReviewUiState(isLoading = false, pending = SampleObservations),
            onConfirm = {},
            onReject = {},
            onUndo = {},
        )
    }
}

@Preview(showBackground = true, name = "Nada que revisar")
@Composable
private fun ReviewScreenEmptyPreview() {
    AvesTheme(dynamicColor = false) {
        ReviewContent(
            state = ReviewUiState(isLoading = false),
            onConfirm = {},
            onReject = {},
            onUndo = {},
        )
    }
}
