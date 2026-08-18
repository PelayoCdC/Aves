package dev.pelayo.aves.feature.identify

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.RegionRepository
import dev.pelayo.aves.core.model.Coordinates
import dev.pelayo.aves.core.designsystem.theme.AvesTheme
import java.io.File
import dev.pelayo.aves.core.designsystem.theme.ScientificNameStyle
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier

/**
 * Pantalla de escucha en directo.
 *
 * @param classifierProvider fabrica del clasificador. Llega desde :app porque es
 *   quien sabe que flavor se ha compilado; devuelve null si el build no incluye
 *   ningun modelo de audio.
 */
@Composable
fun IdentifyScreen(
    classifierProvider: () -> AudioBirdClassifier?,
    observationRepository: ObservationRepository? = null,
    clipsDirectory: File? = null,
    regionRepository: RegionRepository? = null,
    locationProvider: (() -> Coordinates?)? = null,
    /** Abre la camara. La foto es la via secundaria: se usa cuando ves el ave pero no la oyes. */
    onOpenCamera: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val viewModel: IdentifyViewModel = viewModel {
        IdentifyViewModel(
            classifierFactory = classifierProvider,
            observationRepository = observationRepository,
            clipsDirectory = clipsDirectory,
            regionRepository = regionRepository,
            locationProvider = locationProvider,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.toggleListening()
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "audio.wav"
            viewModel.analyzeFile(name) {
                context.contentResolver.openInputStream(uri)
                    ?: error("No se pudo abrir el fichero seleccionado")
            }
        }
    }

    val hasPermission = remember(state.isListening) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when {
                state.isWarmingUp -> "Preparando el modelo..."
                state.isAnalyzingFile -> state.analyzedFileName ?: "Analizando fichero"
                state.isListening -> "Escuchando"
                else -> "Pulsa para escuchar"
            },
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(Modifier.size(4.dp))

        AnimatedVisibility(
            visible = (state.isListening || state.isAnalyzingFile) && !state.isWarmingUp,
        ) {
            Text(
                text = buildString {
                    append("${state.windowsProcessed} ventanas")
                    // Las saltadas son la medida directa de cuánta batería
                    // ahorra el gate de energía.
                    if (state.windowsSkipped > 0) append(" · ${state.windowsSkipped} en silencio")
                    append(" · ${state.lastInferenceMillis} ms")
                    if (state.speciesRecorded > 0) append(" · ${state.speciesRecorded} guardadas")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.size(16.dp))

        state.error?.let { error ->
            ErrorCard(error = error, detail = state.errorDetail)
            Spacer(Modifier.size(16.dp))
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            when {
                state.isWarmingUp -> CircularProgressIndicator()

                state.detections.isEmpty() && state.isListening -> Text(
                    text = "Nada reconocido todavia.\nHacen falta unos segundos de canto seguido.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.detections, key = { it.speciesId }) { detection ->
                        DetectionCard(detection)
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            FloatingActionButton(
                onClick = {
                    if (hasPermission) {
                        viewModel.toggleListening()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape),
            ) {
                Icon(
                    imageVector = if (state.isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (state.isListening) "Parar" else "Escuchar",
                )
            }

            // Analizar un fichero no es solo una comodidad: es lo que permite
            // reproducir un resultado y comparar contra una referencia conocida.
            SmallFloatingActionButton(
                onClick = { filePicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Filled.AudioFile,
                    contentDescription = "Analizar un fichero de audio",
                )
            }

            // La camara vive aqui y no en la barra inferior a proposito: es una
            // tercera forma de identificar, no una seccion aparte de la app.
            onOpenCamera?.let { openCamera ->
                SmallFloatingActionButton(
                    onClick = openCamera,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PhotoCamera,
                        contentDescription = "Identificar con una foto",
                    )
                }
            }
        }

        // Requisito de la licencia CC BY-NC-SA, no un detalle de cortesia.
        state.attribution?.let { attribution ->
            Spacer(Modifier.size(12.dp))
            Text(
                text = attribution,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetectionCard(detection: StableDetection, modifier: Modifier = Modifier) {
    // Formato de BirdNET: "Nombre cientifico_Nombre comun".
    val separator = detection.label.indexOf('_')
    val scientific = if (separator >= 0) detection.label.take(separator) else detection.label
    val common = if (separator >= 0) detection.label.substring(separator + 1) else ""

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (common.isNotEmpty()) {
                Text(text = common, style = MaterialTheme.typography.titleMedium)
            }
            Text(text = scientific, style = ScientificNameStyle)
            Spacer(Modifier.size(8.dp))
            LinearProgressIndicator(
                progress = { detection.score },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = "${(detection.score * 100).toInt()} %",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorCard(error: IdentifyError, detail: String?, modifier: Modifier = Modifier) {
    val (title, help) = when (error) {
        IdentifyError.NO_MODEL_IN_BUILD ->
            "Esta version no incluye modelo de audio" to
                "El flavor `open` todavia no tiene un clasificador con licencia " +
                "Apache. Compila el flavor `personal`."

        IdentifyError.MODEL_FILES_MISSING ->
            "Faltan los ficheros del modelo" to
                "Ejecuta scripts/fetch-models.ps1 y vuelve a compilar. Los modelos " +
                "no se versionan: pesan ~39 MB y son CC BY-NC-SA."

        IdentifyError.MICROPHONE_UNAVAILABLE ->
            "No se pudo abrir el microfono" to
                "Puede que otra aplicacion lo tenga ocupado."

        IdentifyError.UNSUPPORTED_AUDIO ->
            "Ese fichero no se puede analizar" to
                "De momento solo se admite WAV PCM de 16 bits. Conviertelo con: " +
                "ffmpeg -i entrada -ac 1 -ar 48000 -c:a pcm_s16le salida.wav"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.size(4.dp))
            Text(text = help, style = MaterialTheme.typography.bodySmall)
            if (detail != null) {
                Spacer(Modifier.size(4.dp))
                Text(text = detail, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Preview
@Composable
private fun IdentifyScreenPreview() {
    AvesTheme {
        IdentifyScreen(classifierProvider = { null })
    }
}
