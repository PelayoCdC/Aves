package dev.pelayo.aves.feature.identify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pelayo.aves.core.designsystem.theme.AvesTheme
import dev.pelayo.aves.core.designsystem.theme.ScientificNameStyle
import dev.pelayo.aves.core.listening.CandidateGlimpse
import dev.pelayo.aves.core.listening.DetectionNamer
import dev.pelayo.aves.core.listening.StableDetection
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier
import kotlin.math.log10

/**
 * Pantalla de escucha en directo.
 *
 * @param classifierProvider fabrica del clasificador. Llega desde :app porque es
 *   quien sabe que flavor se ha compilado; devuelve null si el build no incluye
 *   ningun modelo de audio.
 * @param namer nombres del catalogo para el analisis de fichero.
 */
@Composable
fun IdentifyScreen(
    classifierProvider: () -> AudioBirdClassifier?,
    namer: DetectionNamer? = null,
    /** Abre la camara. La foto es la via secundaria: se usa cuando ves el ave pero no la oyes. */
    onOpenCamera: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // La escucha en directo corre en un servicio en primer plano, no aqui: asi
    // sobrevive a salir de la app. Los repositorios y la carpeta de recortes se
    // los da la Application al servicio, no esta pantalla.
    val appContext = context.applicationContext
    val viewModel: IdentifyViewModel = viewModel {
        IdentifyViewModel(appContext, classifierProvider, namer)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // La atribucion es requisito de la licencia CC BY-NC-SA, no cortesia. Se lee
    // una vez del descriptor y el clasificador se cierra acto seguido: solo se
    // queria el texto, no cargar decenas de MB de modelo.
    val attribution = remember(classifierProvider) {
        classifierProvider()?.use { it.descriptor.attributionText }
    }

    // --- Permisos -----------------------------------------------------------
    // El microfono es imprescindible. Las notificaciones (Android 13+) y la
    // ubicacion no lo son, pero sin ellas la app funciona a medias: sin
    // notificacion el usuario no ve que el microfono esta abierto, y sin
    // ubicacion no hay prior geo-temporal ni rareza. Se piden todos de una
    // vez la primera vez; despues, solo el que falte, una sola vez.
    var pendingStart by remember { mutableStateOf(false) }
    var askedOptional by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        if (pendingStart && context.hasPermission(Manifest.permission.RECORD_AUDIO)) {
            viewModel.toggleListening()
        }
        pendingStart = false
    }

    val onMicrophoneTap: () -> Unit = {
        val hasMicrophone = context.hasPermission(Manifest.permission.RECORD_AUDIO)
        val optionalMissing = optionalPermissions().filterNot(context::hasPermission)
        when {
            !hasMicrophone -> {
                pendingStart = true
                permissionLauncher.launch((listOf(Manifest.permission.RECORD_AUDIO) + optionalMissing).toTypedArray())
            }

            optionalMissing.isNotEmpty() && !askedOptional && !state.isListening -> {
                askedOptional = true
                pendingStart = true
                permissionLauncher.launch(optionalMissing.toTypedArray())
            }

            else -> viewModel.toggleListening()
        }
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
                state.analyzedFileName != null -> state.analyzedFileName!!
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
                    if (state.isListening) append(if (state.hasLocation) " · con ubicación" else " · sin ubicación")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = state.isListening && !state.isWarmingUp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LiveSpectrogram(
                    columns = state.spectrogram,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .height(72.dp),
                )
                AudioLevelMeter(
                    levelRms = state.levelRms,
                    sourceName = state.audioSource,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Spacer(Modifier.size(16.dp))

        state.error?.let { error ->
            ErrorCard(error = error, detail = state.errorDetail, sampleRate = state.modelSampleRate)
            Spacer(Modifier.size(16.dp))
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            val glimpse = state.glimpse?.takeIf { candidate ->
                state.isListening && state.detections.none { it.speciesId == candidate.speciesId }
            }
            when {
                state.isWarmingUp -> CircularProgressIndicator()

                state.detections.isEmpty() && state.isListening -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Nada confirmado todavía.\nHacen falta unos segundos de canto seguido.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    glimpse?.let { GlimpseRow(it) }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.detections, key = { it.speciesId }) { detection ->
                        DetectionCard(detection)
                    }
                    glimpse?.let { item(key = "glimpse") { GlimpseRow(it) } }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            FloatingActionButton(
                onClick = onMicrophoneTap,
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
        attribution?.let { text ->
            Spacer(Modifier.size(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Los que mejoran la app pero no la bloquean. */
private fun optionalPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
}

/** Nombre comun y cientifico de una deteccion, del catalogo si lo hay y de la etiqueta cruda si no. */
private fun StableDetection.displayNames(): Pair<String, String> {
    val common = commonName
    val scientific = scientificName
    if (common != null && scientific != null) return common to scientific
    // Formato de BirdNET: "Nombre cientifico_Nombre comun".
    val separator = label.indexOf('_')
    val rawScientific = if (separator >= 0) label.take(separator) else label
    val rawCommon = if (separator >= 0) label.substring(separator + 1) else ""
    return (common ?: rawCommon) to (scientific ?: rawScientific)
}

private fun CandidateGlimpse.displayName(): String {
    commonName?.let { return it }
    val separator = label.indexOf('_')
    return if (separator >= 0) label.substring(separator + 1) else label
}

@Composable
private fun DetectionCard(detection: StableDetection, modifier: Modifier = Modifier) {
    val (common, scientific) = detection.displayNames()

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

/**
 * Lo que el modelo esta oyendo AHORA, aunque aun no lo de por bueno.
 *
 * En gris y sin tarjeta a proposito: es una corazonada, no un resultado. Su
 * funcion es que el usuario vea que la app esta trabajando y que distinga "no
 * capta" de "capta pero no confirma", que fue lo que no se pudo saber en la
 * primera prueba de campo.
 */
@Composable
private fun GlimpseRow(glimpse: CandidateGlimpse, modifier: Modifier = Modifier) {
    Text(
        text = "Oyendo… ¿${glimpse.displayName()}? ${(glimpse.score * 100).toInt()} %",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun ErrorCard(
    error: IdentifyError,
    detail: String?,
    sampleRate: Int?,
    modifier: Modifier = Modifier,
) {
    val (title, help) = when (error) {
        IdentifyError.NO_MODEL_IN_BUILD ->
            "Esta versión no incluye modelo de audio" to
                "El build se compiló sin ningún clasificador de cantos. Revisa el " +
                "flavor: `personal` enlaza BirdNET v2.4 y `open` BirdNET+."

        IdentifyError.MODEL_FILES_MISSING ->
            "Faltan los ficheros del modelo" to
                "Ejecuta scripts/fetch-models.ps1 y vuelve a compilar. Los modelos " +
                "no se versionan en el repositorio."

        IdentifyError.MICROPHONE_UNAVAILABLE ->
            "No se pudo abrir el micrófono" to
                "Puede que otra aplicación lo tenga ocupado, o que falte el permiso."

        IdentifyError.UNSUPPORTED_AUDIO ->
            "Ese fichero no se puede analizar" to
                "De momento solo se admite WAV PCM de 16 bits. Conviértelo con: " +
                "ffmpeg -i entrada -ac 1 -ar ${sampleRate ?: 48_000} -c:a pcm_s16le salida.wav"
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

/**
 * Espectrograma en directo: tiempo hacia la derecha, frecuencia hacia arriba.
 *
 * Es la forma mas rapida de VER que el microfono capta un canto antes de que
 * el modelo lo confirme: un trino se dibuja como un garabato claro en la mitad
 * alta, y el trafico como una mancha en la base. Se pinta a mano en un Canvas
 * con un rectangulo por celda; con 160 columnas y 48 bandas son unos miles de
 * rectangulos por fotograma, que a 1,25 fotogramas por segundo no se nota.
 */
@Composable
private fun LiveSpectrogram(columns: List<FloatArray>, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.surfaceVariant
    val quiet = MaterialTheme.colorScheme.surfaceVariant
    val mid = MaterialTheme.colorScheme.primary
    val loud = MaterialTheme.colorScheme.tertiary

    Canvas(modifier = modifier.clip(RoundedCornerShape(6.dp)).background(background)) {
        if (columns.isEmpty()) return@Canvas
        val bands = columns.first().size
        val cellWidth = size.width / SPECTROGRAM_VISIBLE_COLUMNS
        val cellHeight = size.height / bands
        val visible = columns.takeLast(SPECTROGRAM_VISIBLE_COLUMNS)
        // Las columnas nuevas entran por la derecha.
        val offset = SPECTROGRAM_VISIBLE_COLUMNS - visible.size
        visible.forEachIndexed { columnIndex, column ->
            val x = (offset + columnIndex) * cellWidth
            for (band in 0 until bands) {
                val value = column[band]
                if (value <= 0.02f) continue
                // Dos tramos: de callado a medio y de medio a fuerte, para que
                // el rango util no se aplaste en un solo degradado.
                val color = if (value < 0.5f) {
                    lerp(quiet, mid, value * 2f)
                } else {
                    lerp(mid, loud, (value - 0.5f) * 2f)
                }
                drawRect(
                    color = color,
                    topLeft = Offset(x, size.height - (band + 1) * cellHeight),
                    size = Size(cellWidth + 0.5f, cellHeight + 0.5f),
                )
            }
        }
    }
}

/** Cuantas columnas se ven: a 512 muestras por columna y 48 kHz, ~1,7 s. Con hop de 800 ms, ~24 s en pantalla. */
private const val SPECTROGRAM_VISIBLE_COLUMNS = 160

/**
 * Medidor del nivel de audio que entra por el microfono.
 *
 * Existe por una razon concreta: la primera prueba de campo real termino en "no
 * parecia captar nada", y ese sintoma es ambiguo. Puede significar que el
 * microfono no entrega senal, que la entrega pero el gate de energia la
 * descarta, o que todo funciona y simplemente no habia ningun ave cantando.
 * Sin ver el nivel no hay forma de distinguirlos, y estabamos adivinando.
 *
 * Se muestra en dBFS porque el rango util del audio es logaritmico: en escala
 * lineal, todo lo interesante se amontona pegado al cero y la barra no se mueve.
 *
 * La marca vertical es el umbral del gate. A la izquierda de ella, la app
 * descarta la ventana sin gastar inferencia.
 */
@Composable
private fun AudioLevelMeter(
    levelRms: Float,
    sourceName: String?,
    modifier: Modifier = Modifier,
) {
    val dbfs = rmsToDbfs(levelRms)
    val fraction = ((dbfs - METER_FLOOR_DBFS) / -METER_FLOOR_DBFS).coerceIn(0f, 1f)
    val thresholdFraction =
        ((GATE_THRESHOLD_DBFS - METER_FLOOR_DBFS) / -METER_FLOOR_DBFS).coerceIn(0f, 1f)
    val aboveGate = dbfs >= GATE_THRESHOLD_DBFS

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(
                        if (aboveGate) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            // Por debajo del corte la ventana ni se clasifica, y
                            // el color lo dice sin necesidad de leer nada.
                            MaterialTheme.colorScheme.outline
                        },
                    ),
            )
            // La marca del umbral, para que se vea cuanto falta para cruzarlo.
            Box(
                modifier = Modifier
                    .fillMaxWidth(thresholdFraction)
                    .fillMaxHeight()
                    .wrapContentWidth(Alignment.End)
                    .width(2.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }

        Spacer(Modifier.size(4.dp))

        Text(
            text = buildString {
                append(if (levelRms <= 0f) "sin señal" else "${dbfs.toInt()} dBFS")
                if (!aboveGate) append(" · por debajo del umbral")
                sourceName?.let { append(" · $it") }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 0 dBFS es el fondo de escala; el silencio digital tiende a menos infinito. */
private fun rmsToDbfs(rms: Float): Float =
    if (rms <= 0f) METER_FLOOR_DBFS else (20.0 * log10(rms.toDouble())).toFloat()

/** Suelo del medidor. Por debajo de -80 dBFS no hay nada que mirar. */
private const val METER_FLOOR_DBFS = -80f

/** Equivale a EnergyGate.DEFAULT_THRESHOLD_FRACTION (0,001). */
private const val GATE_THRESHOLD_DBFS = -60f

@Suppress("unused")
private val previewColor = Color.Unspecified
