package dev.pelayo.aves.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.pelayo.aves.di.AppContainer
import dev.pelayo.aves.feature.camera.CameraScreen
import dev.pelayo.aves.feature.identify.IdentifyScreen
import dev.pelayo.aves.feature.pokedex.PokedexScreen
import dev.pelayo.aves.feature.pokedex.PokedexViewModel
import dev.pelayo.aves.feature.pokedex.SpeciesDetailScreen
import dev.pelayo.aves.feature.pokedex.SpeciesDetailViewModel
import dev.pelayo.aves.feature.review.ReviewScreen
import dev.pelayo.aves.feature.review.ReviewViewModel
import dev.pelayo.aves.feature.settings.SettingsScreen
import dev.pelayo.aves.feature.settings.SettingsViewModel
import dev.pelayo.aves.feature.settings.settingsStore
import dev.pelayo.aves.ml.createAudioClassifier
import dev.pelayo.aves.ml.createContextPrior
import dev.pelayo.aves.ml.createImageClassifier

/** Ruta de la ficha de especie. Es un destino de detalle, no de primer nivel. */
private const val SPECIES_DETAIL_ROUTE = "species"
private const val SPECIES_ID_ARG = "speciesId"

/**
 * La camara NO es un destino de la barra inferior.
 *
 * La foto es la via secundaria de identificacion: los pajaros se oyen mucho mas
 * de lo que se dejan fotografiar. Darle una pestana propia la pondria al mismo
 * nivel que la escucha y sugeriria que son alternativas equivalentes, cuando en
 * la practica no lo son.
 */
private const val CAMERA_ROUTE = "camera"

@Composable
fun AvesNavHost(
    container: AppContainer,
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    // :app es el unico que sabe que flavor se ha compilado y quien tiene los
    // repositorios, asi que es quien inyecta hacia abajo. Las features reciben
    // fabricas y no saben de donde salen sus dependencias.
    val appContext = LocalContext.current.applicationContext
    // El prior se elige por flavor: `personal` usa el meta-modelo de BirdNET,
    // que se entreno junto al clasificador; `open` usa el derivado de GBIF, que
    // es el unico distribuible.
    val contextPrior = remember(appContext, container) {
        createContextPrior(appContext, container.regionRepository)
    }
    val classifierProvider = remember(appContext, contextPrior) {
        { createAudioClassifier(appContext, contextPrior) }
    }

    NavHost(
        navController = navController,
        startDestination = AvesDestination.IDENTIFY.route,
        modifier = modifier,
    ) {
        composable(AvesDestination.IDENTIFY.route) {
            IdentifyScreen(
                classifierProvider = classifierProvider,
                onOpenCamera = { navController.navigate(CAMERA_ROUTE) },
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
                locationProvider = { container.locationProvider.lastKnown() },
                onIdentified = { speciesId, _ ->
                    navController.navigate("$SPECIES_DETAIL_ROUTE/$speciesId")
                },
            )
        }

        composable(AvesDestination.SETTINGS.route) {
            SettingsScreen(
                viewModelFactory = {
                    SettingsViewModel(
                        store = settingsStore(appContext),
                        observationRepository = container.observationRepository,
                        clipsDirectory = container.clipsDirectory,
                    )
                },
                // Los descriptores salen del flavor compilado: :app es el unico
                // que sabe si BirdNET esta enlazado. La atribucion no es
                // decorativa, es requisito de la licencia CC BY-NC-SA.
                modelDescriptors = remember(appContext, contextPrior) {
                    listOfNotNull(
                        createAudioClassifier(appContext, contextPrior)?.use { it.descriptor },
                        createImageClassifier(appContext, contextPrior).use { it.descriptor },
                    )
                },
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
                        id,
                        container.speciesRepository,
                        container.observationRepository,
                    )
                },
            )
        }
    }
}
