package dev.pelayo.aves.feature.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pelayo.aves.core.designsystem.components.ErrorCard

/**
 * Estado sin permiso.
 *
 * Explica para que sirve la camara antes de volver a pedirla: si el usuario ya
 * dijo que no una vez, el sistema no volvera a mostrar el dialogo por mucho que
 * se llame al launcher, asi que el texto es lo unico que queda.
 */
@Composable
internal fun PermissionRequest(
    attribution: String?,
    onRequest: () -> Unit,
    modifier: Modifier = Modifier,
    /** Denegado para siempre: el boton lleva a los ajustes de la app. */
    blocked: Boolean = false,
    onPickFromGallery: (() -> Unit)? = null,
    error: CameraError? = null,
    errorDetail: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.camera_permission_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.camera_permission_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(R.string.camera_permission_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(24.dp))
        if (blocked) {
            Text(
                text = stringResource(R.string.camera_permission_blocked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(8.dp))
        }
        Button(onClick = onRequest) {
            Text(
                stringResource(
                    if (blocked) R.string.camera_permission_settings else R.string.camera_permission_button,
                ),
            )
        }

        // La galeria no necesita ningun permiso, asi que negar la camara no deja
        // a nadie sin identificar por foto.
        if (onPickFromGallery != null) {
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = onPickFromGallery) {
                Text(stringResource(R.string.camera_permission_gallery))
            }
        }

        if (error != null) {
            Spacer(Modifier.size(16.dp))
            ErrorCard(error = error, detail = errorDetail)
        }

        attribution?.let {
            Spacer(Modifier.size(24.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun ErrorCard(error: CameraError, detail: String?, modifier: Modifier = Modifier) {
    val (title, help) = when (error) {
        CameraError.NO_MODEL_IN_BUILD ->
            stringResource(R.string.camera_error_no_model_title) to
                stringResource(R.string.camera_error_no_model_help)

        CameraError.MODEL_FILES_MISSING ->
            stringResource(R.string.camera_error_files_missing_title) to
                stringResource(R.string.camera_error_files_missing_help)

        CameraError.CAMERA_UNAVAILABLE ->
            stringResource(R.string.camera_error_unavailable_title) to
                stringResource(R.string.camera_error_unavailable_help)

        CameraError.CAPTURE_FAILED ->
            stringResource(R.string.camera_error_capture_title) to
                stringResource(R.string.camera_error_capture_help)
    }

    ErrorCard(title = title, help = help, detail = detail, modifier = modifier)
}

// --- Puente con CameraX ----------------------------------------------------
