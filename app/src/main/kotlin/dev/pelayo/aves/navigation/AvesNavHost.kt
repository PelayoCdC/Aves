package dev.pelayo.aves.navigation

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.pelayo.aves.core.data.RegionRepository
import dev.pelayo.aves.core.model.RarityTier
import dev.pelayo.aves.di.AppContainer
import dev.pelayo.aves.di.ObservationExporter
import dev.pelayo.aves.feature.camera.CameraScreen
import dev.pelayo.aves.feature.identify.IdentifyScreen
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ruta de la ficha de especie. Es un destino de detalle, no de primer nivel. */
private const val SPECIES_DETAIL_ROUTE = "species"
private const val SPECIES_ID_ARG = "speciesId"

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
            }.onFailure { Log.e(TAG, "No se pudo exportar", it) }
        }
    }

    // La ubicacion de la camara y de las observaciones a mano, respetando el
    // modo sin ubicacion.
    val currentLocation: suspend () -> dev.pelayo.aves.core.model.Coordinates? = {
        if (settings.locationDisabled) null else container.locationProvider.current()
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
                onOpenCamera = { navController.navigate(CAMERA_ROUTE) },
                startListeningToken = startListeningToken,
            )
        }

        composable(AvesDestination.POKEDEX.route) {
            PokedexScreen(
                viewModelFactory = {
                    PokedexViewModel(container.speciesRepository, container.observationRepository)
                },
                onSpeciesClick = { speciesId ->
                    navController.navigate("$SPECIES_DETAIL_ROUTE/$speciesId")
                },
                onOpenHistory = { navController.navigate(SESSIONS_ROUTE) },
            )
        }

        composable(AvesDestination.REVIEW.route) {
            ReviewScreen(
                viewModelFactory = {
                    ReviewViewModel(container.observationRepository, container.speciesRepository)
                },
            )
        }

        composable(CAMERA_ROUTE) {
            CameraScreen(
                classifierProvider = { createImageClassifier(appContext, contextPrior) },
                locationProvider = currentLocation,
                contextInfluence = settings.contextInfluence,
                onIdentified = { identification ->
                    // Se guarda ANTES de navegar: la ficha tiene que abrirse ya
                    // con el avistamiento dentro, y con el id canonico del
                    // catalogo, no con el provisional del modelo.
                    scope.launch {
                        val speciesId = runCatching {
                            container.imageObservationRecorder.record(identification)
                        }.onFailure { Log.e(TAG, "No se pudo guardar la foto", it) }
                            .getOrNull() ?: identification.candidate.speciesId
                        if (speciesId != null) {
                            navController.navigate("$SPECIES_DETAIL_ROUTE/$speciesId")
                        }
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
            route = "$SPECIES_DETAIL_ROUTE/{$SPECIES_ID_ARG}",
            arguments = listOf(navArgument(SPECIES_ID_ARG) { type = NavType.StringType }),
        ) { entry ->
            val speciesId = entry.arguments?.getString(SPECIES_ID_ARG).orEmpty()
            SpeciesDetailScreen(
                speciesId = speciesId,
                viewModelFactory = { id ->
                    SpeciesDetailViewModel(
                        speciesId = id,
                        speciesRepository = container.speciesRepository,
                        observationRepository = container.observationRepository,
                        encyclopedia = container.encyclopedia,
                        currentLocation = currentLocation,
                        rarityOf = { species, where ->
                            container.regionRepository.rarityOf(
                                species,
                                where.latitude,
                                where.longitude,
                                RegionRepository.monthOf(System.currentTimeMillis()),
                            ) ?: RarityTier.UNKNOWN
                        },
                    )
                },
            )
        }
    }
}
