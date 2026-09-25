package dev.pelayo.aves.navigation

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.pelayo.aves.R
import dev.pelayo.aves.core.data.RegionRepository
import dev.pelayo.aves.core.model.RarityTier
import dev.pelayo.aves.di.AppContainer
import dev.pelayo.aves.di.ObservationExporter
import dev.pelayo.aves.di.toListeningSettings
import dev.pelayo.aves.feature.camera.CameraScreen
import dev.pelayo.aves.feature.identify.IdentifyScreen
import dev.pelayo.aves.feature.pokedex.AchievementsScreen
import dev.pelayo.aves.feature.pokedex.AchievementsViewModel
import dev.pelayo.aves.feature.pokedex.MapScreen
import dev.pelayo.aves.feature.pokedex.MapViewModel
import dev.pelayo.aves.feature.pokedex.PendingPhotoConfirmation
import dev.pelayo.aves.feature.pokedex.PokedexScreen
import dev.pelayo.aves.feature.pokedex.PokedexViewModel
import dev.pelayo.aves.feature.pokedex.SessionDetailScreen
import dev.pelayo.aves.feature.pokedex.SessionDetailViewModel
import dev.pelayo.aves.feature.pokedex.SessionsScreen
import dev.pelayo.aves.feature.pokedex.SessionsViewModel
import dev.pelayo.aves.feature.pokedex.SpeciesDetailScreen
import dev.pelayo.aves.feature.pokedex.SpeciesDetailViewModel
import dev.pelayo.aves.feature.review.ReviewScreen
import dev.pelayo.aves.feature.review.ReviewViewModel
import dev.pelayo.aves.feature.settings.AvesSettings
import dev.pelayo.aves.feature.settings.SettingsScreen
import dev.pelayo.aves.feature.settings.SettingsViewModel
import dev.pelayo.aves.ml.createAudioClassifier
import dev.pelayo.aves.ml.createImageClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Ruta de la ficha de especie. Es un destino de detalle, no de primer nivel. */
private const val SPECIES_DETAIL_ROUTE = "species"
private const val SPECIES_ID_ARG = "speciesId"

/**
 * Una foto identificada que espera confirmacion en la ficha. El token evita que
 * la misma ficha, abierta despues desde la Pokedex, vuelva a ensenar la tarjeta.
 */
private const val PHOTO_TOKEN_ARG = "photo"

/** Las medallas. Cuelgan de la Pokedex, igual que el historial. */
private const val ACHIEVEMENTS_ROUTE = "achievements"

/** El mapa: de la coleccion entera, o de una especie (`map?speciesId=...`). */
private const val MAP_ROUTE = "map"

/** El historial de escuchas y la ficha de una escucha. Cuelgan de la Pokedex. */
private const val SESSIONS_ROUTE = "sessions"
private const val SESSION_DETAIL_ROUTE = "session"
private const val SESSION_ID_ARG = "sessionId"

/**
 * La camara NO es un destino de la barra inferior.
 *
 * La foto es la via secundaria de identificacion: los pajaros se oyen mucho mas
 * de lo que se dejan fotografiar. Darle una pestana propia la pondria al mismo
 * nivel que la escucha y sugeriria que son alternativas equivalentes, cuando en
 * la practica no lo son.
 */
private const val CAMERA_ROUTE = "camera"

private const val TAG = "AvesNavHost"

/** El idioma de la interfaz, que es castellano fijo: "España" y no "Spain". */
private val INTERFACE_LOCALE = Locale("es")

/**
 * El pais del movil, para cuando no hay ubicacion con la que detectar la zona.
 *
 * Primero la red movil (donde ESTA el telefono) y despues el idioma (de donde
 * ES): alguien con el movil en ingles en Oviedo quiere las aves de España. No
 * pide ningun permiso.
 */
private fun deviceCountryCode(context: Context): String? {
    val telephony = context.getSystemService(TelephonyManager::class.java)
    // Sin cobertura (o en una tablet sin SIM) devuelven cadena vacia, no null.
    val fromNetwork = runCatching { telephony?.networkCountryIso }.getOrNull()?.takeIf { it.length == 2 }
        ?: runCatching { telephony?.simCountryIso }.getOrNull()?.takeIf { it.length == 2 }
    return fromNetwork?.uppercase()
        ?: Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase()
}

/**
 * @param startListeningToken cambia cada vez que algo externo (el tile de
 *   ajustes rapidos) pide arrancar la escucha. Se pasa a la pantalla de
 *   identificar, que es quien sabe pedir permisos y arrancar.
 */
@Composable
fun AvesNavHost(
    container: AppContainer,
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startListeningToken: Int = 0,
    onStartListeningHandled: () -> Unit = {},
) {
    // :app es el unico que sabe que flavor se ha compilado y quien tiene los
    // repositorios, asi que es quien inyecta hacia abajo. Las features reciben
    // fabricas y no saben de donde salen sus dependencias.
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    // Los ajustes del usuario, vivos: la camara y la escucha los leen de aqui.
    val settings by container.settingsStore.settings.collectAsStateWithLifecycle(
        initialValue = AvesSettings(),
    )

    // El prior es el singleton del contenedor: su cache (el barrido anual del
    // geomodelo) tiene que sobrevivir entre pantallas y entre escuchas.
    val contextPrior = container.contextPrior
    val classifierProvider = remember(appContext, contextPrior) {
        { createAudioClassifier(appContext, contextPrior) }
    }

    // Exportar: el sistema pide donde guardar y nosotros escribimos. Sin cuenta,
    // sin servidor: el fichero es del usuario.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ObservationExporter.MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val csv = container.exporter.toCsv()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(csv.toByteArray(Charsets.UTF_8))
                    } ?: error("No se pudo abrir el destino")
                }
            }.onFailure {
                Log.e(TAG, "No se pudo exportar", it)
                Toast.makeText(context, R.string.export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    // La ubicacion de la camara y de las observaciones a mano, respetando el
    // modo sin ubicacion.
    val currentLocation: suspend () -> dev.pelayo.aves.core.model.Coordinates? = {
        if (settings.locationDisabled) null else container.locationProvider.current()
    }

    // La foto identificada que esta esperando a que el usuario la confirme
    // desde la ficha. Antes se guardaba al tocar el candidato y la ficha se
    // abria con el avistamiento ya dentro; ahora la ficha se abre para
    // COMPROBAR, y solo se guarda si el usuario dice que si.
    val photoHolder: PendingPhotoHolder = viewModel()
    val pendingPhoto by photoHolder.pending.collectAsStateWithLifecycle()

    // "¿Donde fue?" para las fotos de la galeria y las grabaciones: lo que no se
    // hizo ahora y aqui necesita que alguien diga donde.
    val placePicker = remember(container) {
        container.placePickerActions(locationDisabled = { container.settingsStore.settings.first().locationDisabled })
    }

    NavHost(
        navController = navController,
        startDestination = AvesDestination.IDENTIFY.route,
        modifier = modifier,
    ) {
        composable(AvesDestination.IDENTIFY.route) {
            IdentifyScreen(
                classifierProvider = classifierProvider,
                namer = container.detectionNamer,
                analysisSaver = container.fileAnalysisSaver,
                settings = { container.settingsStore.settings.first().toListeningSettings() },
                placePicker = placePicker,
                onOpenCamera = { navController.navigate(CAMERA_ROUTE) },
                startListeningToken = startListeningToken,
                onStartListeningHandled = onStartListeningHandled,
            )
        }

        composable(AvesDestination.POKEDEX.route) {
            PokedexScreen(
                viewModelFactory = {
                    PokedexViewModel(
                        speciesRepository = container.speciesRepository,
                        observationRepository = container.observationRepository,
                        checklistRepository = container.checklistRepository,
                        // Ya respeta el modo sin ubicacion: con el apagado, la
                        // zona sale del pais del movil y no de donde esta.
                        currentLocation = currentLocation,
                        deviceCountryCode = { deviceCountryCode(appContext) },
                        countryDisplayName = { code -> Locale("", code).getDisplayCountry(INTERFACE_LOCALE) },
                    )
                },
                onSpeciesClick = { speciesId ->
                    navController.navigate("$SPECIES_DETAIL_ROUTE/$speciesId")
                },
                onOpenHistory = { navController.navigate(SESSIONS_ROUTE) },
                onOpenAchievements = { navController.navigate(ACHIEVEMENTS_ROUTE) },
                onOpenMap = { navController.navigate(MAP_ROUTE) },
            )
        }

        composable(
            route = "$MAP_ROUTE?$SPECIES_ID_ARG={$SPECIES_ID_ARG}",
            arguments = listOf(
                navArgument(SPECIES_ID_ARG) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            MapScreen(
                speciesId = entry.arguments?.getString(SPECIES_ID_ARG),
                viewModelFactory = { id ->
                    MapViewModel(
                        speciesId = id,
                        speciesRepository = container.speciesRepository,
                        observationRepository = container.observationRepository,
                        rangeMaps = container.rangeMapRepository,
                    )
                },
                onSpeciesClick = { speciesId -> navController.navigate("$SPECIES_DETAIL_ROUTE/$speciesId") },
            )
        }

        composable(ACHIEVEMENTS_ROUTE) {
            AchievementsScreen(
                viewModelFactory = {
                    AchievementsViewModel(
                        speciesRepository = container.speciesRepository,
                        observationRepository = container.observationRepository,
                        checklistRepository = container.checklistRepository,
                    )
                },
            )
        }

        composable(AvesDestination.REVIEW.route) {
            ReviewScreen(
                viewModelFactory = {
                    ReviewViewModel(container.observationRepository, container.speciesRepository)
                },
                onSpeciesClick = { speciesId -> navController.navigate("$SPECIES_DETAIL_ROUTE/$speciesId") },
            )
        }

        composable(CAMERA_ROUTE) {
            CameraScreen(
                classifierProvider = { createImageClassifier(appContext, contextPrior) },
                locationProvider = currentLocation,
                contextInfluence = settings.contextInfluence,
                placePicker = placePicker,
                nameCandidates = container.imageCandidateNamer::name,
                searchSpecies = { query -> container.speciesRepository.search(query, limit = 30) },
                onIdentified = { identification ->
                    // NO se guarda todavia: se abre la ficha (con el id canonico
                    // del catalogo, no el provisional del modelo) para que el
                    // usuario compare la foto con la especie y decida.
                    scope.launch {
                        val speciesId = container.imageCandidateNamer.speciesIdOf(identification) ?: return@launch
                        val token = photoHolder.hold(identification)
                        navController.navigate("$SPECIES_DETAIL_ROUTE/$speciesId?$PHOTO_TOKEN_ARG=$token")
                    }
                },
            )
        }

        composable(AvesDestination.SETTINGS.route) {
            SettingsScreen(
                viewModelFactory = {
                    SettingsViewModel(
                        store = container.settingsStore,
                        observationRepository = container.observationRepository,
                        clipsDirectory = container.clipsDirectory,
                        photosDirectory = container.photosDirectory,
                    )
                },
                // Los descriptores salen del flavor compilado: :app es el unico
                // que sabe que modelos estan enlazados. La atribucion no es
                // decorativa, es requisito de las licencias.
                modelDescriptors = remember(appContext, contextPrior) {
                    listOfNotNull(
                        createAudioClassifier(appContext, contextPrior)?.use { it.descriptor },
                        createImageClassifier(appContext, contextPrior).use { it.descriptor },
                    )
                },
                onExportData = {
                    exportLauncher.launch(ObservationExporter.suggestedFileName(System.currentTimeMillis()))
                },
            )
        }

        composable(SESSIONS_ROUTE) {
            SessionsScreen(
                viewModelFactory = {
                    SessionsViewModel(container.observationRepository, container::deleteSession)
                },
                onSessionClick = { sessionId -> navController.navigate("$SESSION_DETAIL_ROUTE/$sessionId") },
            )
        }

        composable(
            route = "$SESSION_DETAIL_ROUTE/{$SESSION_ID_ARG}",
            arguments = listOf(navArgument(SESSION_ID_ARG) { type = NavType.LongType }),
        ) { entry ->
            val sessionId = entry.arguments?.getLong(SESSION_ID_ARG) ?: 0L
            SessionDetailScreen(
                sessionId = sessionId,
                viewModelFactory = { id ->
                    SessionDetailViewModel(id, container.observationRepository, container.speciesRepository)
                },
                onSpeciesClick = { speciesId -> navController.navigate("$SPECIES_DETAIL_ROUTE/$speciesId") },
            )
        }

        composable(
            route = "$SPECIES_DETAIL_ROUTE/{$SPECIES_ID_ARG}?$PHOTO_TOKEN_ARG={$PHOTO_TOKEN_ARG}",
            arguments = listOf(
                navArgument(SPECIES_ID_ARG) { type = NavType.StringType },
                navArgument(PHOTO_TOKEN_ARG) {
                    type = NavType.IntType
                    defaultValue = 0
                },
            ),
        ) { entry ->
            val speciesId = entry.arguments?.getString(SPECIES_ID_ARG).orEmpty()
            val token = entry.arguments?.getInt(PHOTO_TOKEN_ARG) ?: 0
            val awaiting = pendingPhoto?.takeIf { token != 0 && it.token == token }?.identification
            SpeciesDetailScreen(
                speciesId = speciesId,
                onOpenMap = { navController.navigate("$MAP_ROUTE?$SPECIES_ID_ARG=$speciesId") },
                placePicker = placePicker,
                pendingPhoto = awaiting?.let { identification ->
                    PendingPhotoConfirmation(
                        preview = identification.fullPhoto ?: identification.photo,
                        onConfirm = {
                            // Se saca de la espera ANTES de guardar: la tarjeta
                            // desaparece al primer toque y un segundo no encuentra
                            // nada que guardar.
                            val confirmed = photoHolder.take(token)
                            if (confirmed != null) {
                                scope.launch {
                                    runCatching { container.imageObservationRecorder.record(confirmed) }
                                        .onFailure {
                                            Log.e(TAG, "No se pudo guardar la foto", it)
                                            Toast.makeText(context, R.string.photo_save_failed, Toast.LENGTH_LONG).show()
                                        }
                                }
                            }
                        },
                        onReject = {
                            photoHolder.discard()
                            navController.popBackStack()
                        },
                    )
                },
                viewModelFactory = { id ->
                    SpeciesDetailViewModel(
                        speciesId = id,
                        speciesRepository = container.speciesRepository,
                        observationRepository = container.observationRepository,
                        encyclopedia = container.encyclopedia,
                        rangeMaps = container.rangeMapRepository,
                        currentLocation = currentLocation,
                        rarityAt = { species, where, epochMillis ->
                            // El sitio de un avistamiento corregido a mano casi nunca
                            // es una celda ya descargada: se pide, y la rareza se
                            // rellena sola cuando llegue.
                            container.regionWarmer.warm(where)
                            container.regionRepository.rarityOf(
                                species,
                                where.latitude,
                                where.longitude,
                                RegionRepository.monthOf(epochMillis),
                            ) ?: RarityTier.UNKNOWN
                        },
                        savePhoto = container.galleryPhotoSaver::save,
                    )
                },
            )
        }
    }
}
