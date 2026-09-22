package dev.pelayo.aves.feature.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pelayo.aves.core.designsystem.components.PickedPlace
import dev.pelayo.aves.core.designsystem.components.PlaceChip
import dev.pelayo.aves.core.designsystem.components.PlacePickerActions
import dev.pelayo.aves.core.designsystem.components.PlacePickerDialog
import dev.pelayo.aves.core.designsystem.theme.AvesTheme
import dev.pelayo.aves.core.designsystem.theme.ScientificNameStyle
import dev.pelayo.aves.core.ml.api.ImageBirdClassifier
import dev.pelayo.aves.core.ml.api.ObservationContext
import dev.pelayo.aves.core.model.Coordinates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Identificacion por foto.
 *
 * Es la via secundaria de la app: se usa cuando ves al ave pero no la oyes. Por
 * eso la pantalla no intenta competir con la de escucha en riqueza; hace una
 * sola cosa -encuadrar, disparar, proponer- y devuelve la decision al usuario.
 *
 * @param classifierProvider fabrica del clasificador. Llega desde :app porque es
 *   quien sabe que flavor se ha compilado. Devuelve null si el build no incluye
 *   modelo de imagen.
 * @param locationProvider ubicacion aproximada, pedida en el momento del
 *   disparo. Sin ella el prior geo-temporal no filtra nada y el modelo tiene
 *   que elegir entre las 964 especies del mundo en vez de entre las plausibles.
 * @param contextInfluence cuanto pesa el prior frente al modelo, de los ajustes.
 * @param onIdentified se invoca cuando el usuario elige uno de los candidatos.
 *   No se dispara solo con el mejor a proposito: en este proyecto la maquina
 *   propone y la persona decide, igual que en la bandeja de revision.
 */
@Composable
fun CameraScreen(
    classifierProvider: () -> ImageBirdClassifier?,
    locationProvider: (suspend () -> Coordinates?)? = null,
    contextInfluence: Float = DEFAULT_CONTEXT_INFLUENCE,
    onIdentified: (ImageIdentification) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Para decir donde se hizo una foto de la galeria. Nulo esconde el selector. */
    placePicker: PlacePickerActions? = null,
    /** Nombre comun del catalogo para cada candidato. Ver [CameraViewModel]. */
    nameCandidates: suspend (modelId: String, List<ImageCandidate>) -> List<ImageCandidate> = { _, c -> c },
) {
    val viewModel: CameraViewModel = viewModel {
        CameraViewModel(classifierFactory = classifierProvider, nameCandidates = nameCandidates)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }

    // Se reevalua al volver a la pantalla: si el usuario fue a los ajustes del
    // sistema a conceder el permiso a mano, no hay dialogo que avise.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermission = context.hasCameraPermission()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) viewModel.prepare()
    }

    // La galeria NO necesita el permiso de camara ni ningun otro: el selector de
    // fotos del sistema entrega solo la imagen que el usuario toca. Por eso
    // esta disponible tambien cuando la camara se ha denegado.
    val scope = rememberCoroutineScope()
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val image = context.loadGalleryImage(uri, state.inputSize)
                viewModel.classifyPicked(
                    display = image.display,
                    modelInput = image.modelInput,
                    // La fecha de la foto, no la de hoy. Y el lugar solo si el
                    // usuario lo ha dicho (se conserva de una foto a la siguiente):
                    // una foto de la galeria puede ser de cualquier sitio, y el
                    // contexto de AQUI no dice nada de ella.
                    context = ObservationContext(
                        latitude = state.pickedPlace?.latitude,
                        longitude = state.pickedPlace?.longitude,
                        epochMillis = image.takenAtMillis ?: System.currentTimeMillis(),
                        contextInfluence = if (state.pickedPlace != null) contextInfluence else 0f,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel.reportCaptureFailure(e)
            }
        }
    }
    val onPickFromGallery: () -> Unit = {
        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val picked = state.pickedImage
    if (picked != null) {
        PickedImageContent(
            image = picked,
            state = state,
            onPickFromGallery = onPickFromGallery,
            onDismissResults = viewModel::dismissResults,
            onIdentified = { candidate -> onIdentified(viewModel.identification(candidate)) },
            modifier = modifier,
            placePicker = placePicker,
            onPlacePicked = { place -> viewModel.reclassifyWithPlace(place, contextInfluence) },
        )
        return
    }

    if (!hasPermission) {
        PermissionRequest(
            attribution = state.attribution,
            onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onPickFromGallery = onPickFromGallery,
            error = state.error,
            errorDetail = state.errorDetail,
            modifier = modifier,
        )
        return
    }

    CameraContent(
        state = state,
        locationProvider = locationProvider,
        contextInfluence = contextInfluence,
        onCapture = { photo, where -> viewModel.classify(photo.modelInput, where, fullPhoto = photo.display) },
        onCaptureFailed = viewModel::reportCaptureFailure,
        onCameraFailed = viewModel::reportCameraFailure,
        onDismissResults = viewModel::dismissResults,
        onIdentified = { candidate -> onIdentified(viewModel.identification(candidate)) },
        onPickFromGallery = onPickFromGallery,
        modifier = modifier,
    )
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/**
 * La vista previa y todo lo que hay encima.
 *
 * Separado de [CameraScreen] porque es la unica parte que necesita camara de
 * verdad: los estados sin camara viven en composables aparte y por eso se
 * pueden previsualizar.
 */
@Composable
private fun CameraContent(
    state: CameraUiState,
    locationProvider: (suspend () -> Coordinates?)?,
    contextInfluence: Float,
    onCapture: (CapturedPhoto, ObservationContext) -> Unit,
    onCaptureFailed: (Throwable) -> Unit,
    onCameraFailed: (Throwable) -> Unit,
    onDismissResults: () -> Unit,
    onIdentified: (ImageCandidate) -> Unit,
    onPickFromGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val previewView = remember(context) {
        PreviewView(context).apply {
            // FIT_CENTER y no FILL_CENTER: asi se ve el fotograma COMPLETO del
            // sensor y el cuadro guia marca exactamente lo que va a recibir el
            // modelo. Con FILL_CENTER la vista previa ya viene recortada por su
            // cuenta y el usuario no tendria forma de saber que queda fuera.
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    // La misma relacion de aspecto en la vista previa y en la captura. Sin
    // fijarla, cada fabricante elige la suya para cada caso de uso y el cuadro
    // guia dejaria de coincidir con lo que recibe el modelo.
    val resolutionSelector = remember {
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
    }

    val imageCapture = remember {
        ImageCapture.Builder()
            // El ave no espera. Vale mas una foto algo menos limpia disparada a
            // tiempo que una perfecta del arbusto que acaba de dejar vacio.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(resolutionSelector)
            .build()
    }

    // Con la app bloqueada en vertical, la rotacion de pantalla no cambia al
    // girar el telefono y `rotationDegrees` enderezaria respecto a la pantalla,
    // no respecto al mundo: un ave fotografiada en apaisado llegaria tumbada al
    // modelo. El sensor de orientacion si sabe como esta sujeto el telefono.
    DisposableEffect(context, imageCapture) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                imageCapture.targetRotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    // Decodificar el JPEG y escalarlo cuesta decenas de milisegundos. En el hilo
    // principal eso es un tiron visible justo al disparar.
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(captureExecutor) {
        onDispose { captureExecutor.shutdown() }
    }

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    LaunchedEffect(Unit) {
        try {
            cameraProvider = context.awaitCameraProvider()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onCameraFailed(e)
        }
    }

    DisposableEffect(cameraProvider, lifecycleOwner) {
        val provider = cameraProvider
        if (provider != null) {
            val preview = CameraPreview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            try {
                // Sin unbindAll, volver a esta pantalla acumula enlaces sobre la
                // misma camara y el segundo falla.
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                )
            } catch (e: Exception) {
                onCameraFailed(e)
            }
        }
        onDispose { provider?.unbindAll() }
    }

    val onShutter: () -> Unit = {
        scope.launch {
            try {
                val photo = imageCapture.capturePhoto(captureExecutor, state.inputSize)
                // Se consulta en el momento del disparo y no al abrir la
                // pantalla: entre encuadrar y disparar puede pasar un rato, y
                // una foto tomada tras andar un trecho merece la ubicacion de
                // donde se tomo. Una sola lectura: invocarlo dos veces podria
                // dar latitud y longitud de lecturas distintas.
                val where = locationProvider?.invoke()
                onCapture(
                    photo,
                    ObservationContext(
                        latitude = where?.latitude,
                        longitude = where?.longitude,
                        epochMillis = System.currentTimeMillis(),
                        contextInfluence = contextInfluence,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onCaptureFailed(e)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        FramingGuide(Modifier.fillMaxSize())

        CaptureOverlay(
            state = state,
            onShutter = onShutter,
            onDismissResults = onDismissResults,
            onIdentified = onIdentified,
            onPickFromGallery = onPickFromGallery,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Una foto de la galeria en lugar de la vista previa.
 *
 * Se ensena ENTERA (ajustada, sin recortar) aunque el modelo solo vea su
 * cuadrado central, y el texto lo dice: recortarla en pantalla esconderia al
 * usuario justo el motivo por el que un ave en una esquina no sale.
 */
@Composable
private fun PickedImageContent(
    image: Bitmap,
    state: CameraUiState,
    onPickFromGallery: () -> Unit,
    onDismissResults: () -> Unit,
    onIdentified: (ImageCandidate) -> Unit,
    modifier: Modifier = Modifier,
    placePicker: PlacePickerActions? = null,
    onPlacePicked: (PickedPlace?) -> Unit = {},
) {
    val picture = remember(image) { image.asImageBitmap() }
    var showPlacePicker by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Image(
            bitmap = picture,
            contentDescription = stringResource(R.string.camera_picked_image),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        // "¿Donde fue?" va ABAJO, junto a los candidatos, que es donde se mira.
        // La primera version lo ponia arriba y quedaba debajo de la barra de
        // estado (la app dibuja de borde a borde): nadie lo vio. Una foto de la
        // galeria no se hizo aqui, y sin lugar el modelo decide a ciegas entre
        // las aves de todo el planeta.
        CaptureOverlay(
            state = state,
            onShutter = {},
            onDismissResults = onDismissResults,
            onIdentified = onIdentified,
            onPickFromGallery = onPickFromGallery,
            fromGallery = true,
            placeChip = if (placePicker != null) {
                { PlaceChip(place = state.pickedPlace, onClick = { showPlacePicker = true }) }
            } else {
                null
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    if (showPlacePicker && placePicker != null) {
        PlacePickerDialog(
            actions = placePicker,
            onPick = { place ->
                onPlacePicked(place)
                showPlacePicker = false
            },
            onDismiss = { showPlacePicker = false },
        )
    }
}

/**
 * El cuadro que marca lo que ve el modelo.
 *
 * No es decoracion: la entrada es un recorte cuadrado centrado, y sin marcarlo
 * el usuario encuadraria al ave en una esquina y se quedaria fuera. Un ave a
 * medias sigue dando candidatos, solo que malos, y ese es el fallo que mas
 * cuesta diagnosticar.
 *
 * Es exacto porque la vista previa y la captura comparten relacion de aspecto
 * (ver el `ResolutionSelector` de arriba).
 */
@Composable
private fun FramingGuide(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        Box(
            Modifier
                .size(side)
                .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
        )
    }
}

/**
 * Todo lo que se dibuja encima de la vista previa.
 *
 * Sin ViewModel ni CameraX, para poder previsualizarlo.
 */
@Composable
internal fun CaptureOverlay(
    state: CameraUiState,
    onShutter: () -> Unit,
    onDismissResults: () -> Unit,
    onIdentified: (ImageCandidate) -> Unit,
    modifier: Modifier = Modifier,
    onPickFromGallery: (() -> Unit)? = null,
    /** true cuando lo que hay debajo es una foto de la galeria y no la camara. */
    fromGallery: Boolean = false,
    /** El chip de "¿donde fue?", que se pinta encima de los resultados. Solo con galeria. */
    placeChip: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        state.error?.let { error ->
            ErrorCard(error = error, detail = state.errorDetail)
            Spacer(Modifier.size(12.dp))
        }

        placeChip?.let { chip ->
            MaterialSurface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            ) { chip() }
            Spacer(Modifier.size(12.dp))
        }

        when {
            state.candidates.isNotEmpty() -> CandidateList(
                candidates = state.candidates,
                onIdentified = onIdentified,
                onDismiss = onDismissResults,
            )

            // Que el modelo no vea ningun ave es un resultado, no un fallo, y hay
            // que decirlo con esas palabras: si no, el usuario cree que la app se
            // ha roto y vuelve a disparar a lo mismo.
            state.hasResult && state.error == null -> NothingRecognized(onDismiss = onDismissResults)
        }

        Spacer(Modifier.size(20.dp))

        // Ocupa el sitio del disparador mientras se piensa, en vez de dejar un
        // boton apagado que invita a insistir con el dedo.
        if (state.isWarmingUp || state.isClassifying) {
            Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        } else if (fromGallery) {
            // Con una foto de la galeria no hay nada que disparar: se elige otra
            // o se vuelve a la camara.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismissResults) {
                    Text(stringResource(R.string.camera_back_to_camera), color = Color.White)
                }
                if (onPickFromGallery != null) {
                    Button(onClick = onPickFromGallery) {
                        Text(stringResource(R.string.camera_pick_another))
                    }
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                // Hueco del mismo ancho que el boton de galeria, para que el
                // disparador quede centrado de verdad.
                Spacer(Modifier.size(48.dp))
                ShutterButton(
                    enabled = state.error == null,
                    onClick = onShutter,
                )
                if (onPickFromGallery != null) {
                    FilledTonalIconButton(onClick = onPickFromGallery, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.Filled.PhotoLibrary,
                            contentDescription = stringResource(R.string.camera_gallery),
                        )
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
            }
        }

        Spacer(Modifier.size(12.dp))

        OverlayLabel(
            text = stringResource(
                when {
                    state.isWarmingUp -> R.string.camera_warming
                    state.isClassifying -> R.string.camera_classifying
                    state.candidates.isNotEmpty() -> R.string.camera_pick_candidate
                    fromGallery -> R.string.camera_picked_hint
                    else -> R.string.camera_frame_hint
                },
            ),
        )

        // Requisito de licencia, igual que en la pantalla de escucha.
        state.attribution?.let { attribution ->
            Spacer(Modifier.size(8.dp))
            OverlayLabel(text = attribution, alpha = 0.7f)
        }
    }
}

@Composable
private fun CandidateList(
    candidates: List<ImageCandidate>,
    onIdentified: (ImageCandidate) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MaterialSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        // Semitransparente para no perder de vista al ave mientras se decide:
        // la comparacion entre la foto y el nombre es justo lo que hay que hacer.
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.camera_could_be),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))

            candidates.forEach { candidate ->
                CandidateRow(candidate = candidate, onIdentified = onIdentified)
                Spacer(Modifier.size(12.dp))
            }

            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.camera_none_of_these))
            }
        }
    }
}

/**
 * Un candidato. Solo el nombre: el porcentaje se quito porque no ayudaba a
 * decidir (la decision es mirar al ave y al nombre) y un "23 %" junto a una
 * especie hacia desconfiar de las tres. El orden ya dice cual es la mas
 * probable, y la confianza se guarda con la observacion para quien la quiera
 * auditar.
 */
@Composable
private fun CandidateRow(
    candidate: ImageCandidate,
    onIdentified: (ImageCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectable = candidate.speciesId != null
    val common = candidate.commonName

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                enabled = selectable,
                onClickLabel = stringResource(R.string.camera_confirm_candidate, common ?: candidate.scientificName),
                role = Role.Button,
            ) { onIdentified(candidate) }
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        // El nombre en castellano manda; el cientifico, debajo, como en la
        // Pokedex. Si el catalogo no lo conoce, solo hay cientifico.
        if (common != null) {
            Text(text = common, style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = candidate.scientificName,
            style = ScientificNameStyle,
            color = if (common != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun NothingRecognized(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    MaterialSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.camera_nothing_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = stringResource(R.string.camera_nothing_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = stringResource(R.string.camera_nothing_audio_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.camera_retry))
            }
        }
    }
}

/**
 * Disparador.
 *
 * Circulo blanco dibujado a mano y no un color del tema: esta encima de una
 * imagen en directo cuyo color no se puede prever, y ahi el unico contraste
 * fiable es el del propio boton contra su borde.
 */
@Composable
private fun ShutterButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha = if (enabled) 1f else 0.35f

    Box(
        modifier = modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(3.dp, Color.White.copy(alpha = alpha), CircleShape)
            .clickable(
                enabled = enabled,
                onClickLabel = stringResource(R.string.camera_shutter),
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = alpha)),
        )
    }
}

/**
 * Texto sobre la imagen en directo.
 *
 * Lleva su propia caja oscura porque el fondo puede ser cielo blanco: sin ella
 * el texto desaparece justo en el escenario mas comun, un ave contra el cielo.
 */
@Composable
private fun OverlayLabel(
    text: String,
    modifier: Modifier = Modifier,
    alpha: Float = 0.85f,
) {
    MaterialSurface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.45f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = alpha),
        )
    }
}

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
        Button(onClick = onRequest) {
            Text(stringResource(R.string.camera_permission_button))
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
private fun ErrorCard(error: CameraError, detail: String?, modifier: Modifier = Modifier) {
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

// --- Puente con CameraX ----------------------------------------------------

/**
 * Espera al proveedor de camara del proceso.
 *
 * `getInstance` devuelve un ListenableFuture y no una corrutina; envolverlo aqui
 * mantiene el resto de la pantalla en el mismo estilo suspendido que el de
 * audio.
 */
private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                // runCatching y no try/resume: si `resume` quedara dentro del
                // try, un fallo suyo intentaria reanudar dos veces la misma
                // continuacion.
                val result = runCatching { future.get() }
                result.onSuccess { if (continuation.isActive) continuation.resume(it) }
                result.onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
            },
            ContextCompat.getMainExecutor(this),
        )
        continuation.invokeOnCancellation { future.cancel(false) }
    }

/**
 * Dispara y devuelve la foto en sus dos tamanos: la entera para guardarla y el
 * recorte cuadrado de [targetSize] pixeles para el modelo.
 *
 * Toda la conversion ocurre dentro del callback, en el hilo de [executor], para
 * que el [ImageProxy] se cierre en el mismo `finally` que lo abrio. CameraX
 * trabaja con un numero fijo de buffers: una sola ruta de salida que no cierre
 * y la camara deja de disparar tras unas pocas fotos, sin error visible.
 */
private suspend fun ImageCapture.capturePhoto(
    executor: Executor,
    targetSize: Int,
): CapturedPhoto = suspendCancellableCoroutine { continuation ->
    takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val prepared = image.toCapturedPhoto(targetSize)
                    if (continuation.isActive) continuation.resume(prepared)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                if (continuation.isActive) continuation.resumeWithException(exception)
            }
        },
    )
}

/**
 * Saca el JPEG del plano 0 y lo convierte en la foto y en la entrada del modelo.
 *
 * `OnImageCapturedCallback` entrega JPEG por defecto, no YUV: el plano 0 no es
 * un plano de luminancia sino el fichero comprimido entero, y hay que
 * decodificarlo.
 */
private fun ImageProxy.toCapturedPhoto(targetSize: Int): CapturedPhoto {
    val buffer = planes[0].buffer
    // El buffer puede llegar con la posicion consumida si alguien lo miro antes.
    buffer.rewind()
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return decodeCapturedPhoto(bytes, imageInfo.rotationDegrees, targetSize)
}

/** Valor de fabrica del peso del contexto cuando la pantalla no recibe los ajustes. */
private const val DEFAULT_CONTEXT_INFLUENCE = 0.5f

// --- Previews ---------------------------------------------------------------
// La vista previa de la camara no se puede renderizar en el editor, asi que lo
// que se previsualiza es la capa de encima y los estados sin camara. Es justo
// donde estan las decisiones de diseno que hay que poder mirar.

@Preview(showBackground = true, name = "Sin permiso")
@Composable
private fun PermissionRequestPreview() {
    AvesTheme(dynamicColor = false) {
        PermissionRequest(
            attribution = SampleAttribution,
            onRequest = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF33421F, name = "Con candidatos")
@Composable
private fun CaptureOverlayPreview() {
    AvesTheme(dynamicColor = false) {
        CaptureOverlay(
            state = CameraUiState(
                candidates = listOf(
                    ImageCandidate("turdus_merula", "Turdus merula", 0.87f, 0.84f, 812),
                    ImageCandidate("turdus_philomelos", "Turdus philomelos", 0.21f, 0.25f, 813),
                    ImageCandidate("sturnus_unicolor", "Sturnus unicolor", 0.07f, 0.09f, 700),
                ),
                hasResult = true,
                attribution = SampleAttribution,
            ),
            onShutter = {},
            onDismissResults = {},
            onIdentified = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF33421F, name = "Encuadrando")
@Composable
private fun CaptureOverlayIdlePreview() {
    AvesTheme(dynamicColor = false) {
        CaptureOverlay(
            state = CameraUiState(attribution = SampleAttribution),
            onShutter = {},
            onDismissResults = {},
            onIdentified = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF33421F, name = "Nada reconocido")
@Composable
private fun CaptureOverlayEmptyPreview() {
    AvesTheme(dynamicColor = false) {
        CaptureOverlay(
            state = CameraUiState(hasResult = true, attribution = SampleAttribution),
            onShutter = {},
            onDismissResults = {},
            onIdentified = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF33421F, name = "Sin modelo")
@Composable
private fun CaptureOverlayNoModelPreview() {
    AvesTheme(dynamicColor = false) {
        CaptureOverlay(
            state = CameraUiState(error = CameraError.NO_MODEL_IN_BUILD),
            onShutter = {},
            onDismissResults = {},
            onIdentified = {},
        )
    }
}

private const val SampleAttribution =
    "Modelo AIY Birds V1 de Google · Apache 2.0 · 964 especies"
