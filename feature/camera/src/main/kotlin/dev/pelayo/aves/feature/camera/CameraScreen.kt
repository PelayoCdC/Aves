package dev.pelayo.aves.feature.camera

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pelayo.aves.core.designsystem.components.DayPickerDialog
import dev.pelayo.aves.core.designsystem.components.PickedPlace
import dev.pelayo.aves.core.designsystem.components.PlaceChip
import dev.pelayo.aves.core.designsystem.components.PlacePickerActions
import dev.pelayo.aves.core.designsystem.components.PlacePickerDialog
import dev.pelayo.aves.core.designsystem.components.formatDay
import dev.pelayo.aves.core.designsystem.components.hasPermission
import dev.pelayo.aves.core.designsystem.components.isPermanentlyDenied
import dev.pelayo.aves.core.designsystem.components.openAppSettings
import dev.pelayo.aves.core.designsystem.theme.AvesTheme
import dev.pelayo.aves.core.ml.api.ImageBirdClassifier
import dev.pelayo.aves.core.ml.api.ObservationContext
import dev.pelayo.aves.core.model.Coordinates
import dev.pelayo.aves.core.model.Species
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

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
    /** Buscar una especie en el catalogo, para cuando ningun candidato convence. Nulo esconde el boton. */
    searchSpecies: ((String) -> Flow<List<Species>>)? = null,
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

    // Tras un "no" definitivo el dialogo ya no sale: el boton pasa a llevar a
    // los ajustes de la app en vez de parecer que no hace nada.
    var permissionBlocked by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        permissionBlocked = !granted && context.isPermanentlyDenied(Manifest.permission.CAMERA)
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

    // "Se cual es": el buscador del catalogo, con la camara o con la galeria.
    var showSearch by remember { mutableStateOf(false) }
    if (showSearch && searchSpecies != null) {
        SpeciesSearchDialog(
            search = searchSpecies,
            onPick = { species ->
                showSearch = false
                onIdentified(viewModel.identificationFor(species))
            },
            onDismiss = { showSearch = false },
        )
    }
    val onSearchSpecies: (() -> Unit)? = if (searchSpecies != null) { { showSearch = true } } else null

    // Con un resultado en pantalla (una foto de la galeria o los candidatos de
    // una captura), atras cierra el resultado y vuelve a la camara, en vez de
    // salir de la camara con el resultado perdido.
    BackHandler(enabled = state.pickedImage != null || state.hasResult) {
        viewModel.dismissResults()
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
            onPlacePicked = { place -> viewModel.reclassifyWith(place, null, contextInfluence) },
            onDatePicked = { millis -> viewModel.reclassifyWith(state.pickedPlace, millis, contextInfluence) },
            onSearchSpecies = onSearchSpecies,
        )
        return
    }

    if (!hasPermission) {
        PermissionRequest(
            attribution = state.attribution,
            blocked = permissionBlocked,
            onRequest = {
                if (permissionBlocked) context.openAppSettings() else permissionLauncher.launch(Manifest.permission.CAMERA)
            },
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
        onSearchSpecies = onSearchSpecies,
        modifier = modifier,
    )
}

private fun Context.hasCameraPermission(): Boolean = hasPermission(Manifest.permission.CAMERA)

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
    onSearchSpecies: (() -> Unit)? = null,
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
            onSearchSpecies = onSearchSpecies,
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
    onDatePicked: (Long) -> Unit = {},
    onSearchSpecies: (() -> Unit)? = null,
) {
    val picture = remember(image) { image.asImageBitmap() }
    var showPlacePicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

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
            // Donde y cuando: las dos cosas que el contexto necesita y que una foto
            // de la galeria no trae (o trae solo la fecha, en el EXIF).
            placeChip = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (placePicker != null) {
                        PlaceChip(place = state.pickedPlace, onClick = { showPlacePicker = true }, modifier = Modifier.widthIn(max = 220.dp))
                    }
                    AssistChip(
                        onClick = { showDatePicker = true },
                        label = { Text(formatDay(state.pickedDateMillis ?: System.currentTimeMillis())) },
                        leadingIcon = {
                            Icon(Icons.Filled.CalendarMonth, contentDescription = stringResource(R.string.camera_pick_date), modifier = Modifier.size(18.dp))
                        },
                    )
                }
            },
            onSearchSpecies = onSearchSpecies,
            modifier = Modifier.fillMaxSize(),
        )
    }

    if (showDatePicker) {
        DayPickerDialog(
            currentMillis = state.pickedDateMillis ?: System.currentTimeMillis(),
            onPick = { millis ->
                onDatePicked(millis)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
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
