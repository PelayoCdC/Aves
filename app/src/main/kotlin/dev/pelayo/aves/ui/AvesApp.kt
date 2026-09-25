package dev.pelayo.aves.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.pelayo.aves.R
import dev.pelayo.aves.di.AppContainer
import dev.pelayo.aves.navigation.AvesDestination
import dev.pelayo.aves.navigation.AvesNavHost
import dev.pelayo.aves.navigation.subScreenTitle
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvesApp(
    container: AppContainer,
    startListeningToken: Int = 0,
    onStartListeningHandled: () -> Unit = {},
) {
    val navController = rememberNavController()

    // Una peticion de escucha desde fuera lleva a la pantalla de identificar,
    // que es la que sabe arrancar. Si ya estaba ahi, no navega a ningun sitio.
    LaunchedEffect(startListeningToken) {
        if (startListeningToken > 0) {
            navController.navigate(AvesDestination.IDENTIFY.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // La pestana de la que cuelga lo que se ve: la ultima de primer nivel que
    // se visito. Las subpantallas se abren siempre desde la pestana en la que
    // se esta, asi que en la ficha de una especie abierta desde Revisar sigue
    // resaltada Revisar, y no ninguna como antes.
    var currentTab by rememberSaveable { mutableStateOf(AvesDestination.IDENTIFY.route) }
    LaunchedEffect(currentRoute) {
        if (AvesDestination.entries.any { it.route == currentRoute }) currentTab = currentRoute!!
    }
    val subScreenTitle = subScreenTitle(currentRoute)

    // El contador de pendientes en la pestaña no es decoracion: sin el, la
    // bandeja de revision se olvida, las detecciones se quedan sin confirmar y
    // la coleccion nunca crece aunque la app este identificando bien.
    val pendingFlow = remember(container) {
        container.observationRepository.pendingReview().map { it.size }
    }
    val pendingCount by pendingFlow.collectAsStateWithLifecycle(initialValue = 0)

    Scaffold(
        // Las pantallas que no son pestana llevan barra con flecha de volver:
        // antes solo se salia con el gesto del sistema, y no habia forma de
        // saber donde se estaba.
        topBar = {
            if (subScreenTitle != null) {
                TopAppBar(
                    title = { Text(stringResource(subScreenTitle)) },
                    navigationIcon = {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.navigate_up),
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                AvesDestination.entries.forEach { destination ->
                    val selected = currentTab == destination.route

                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                // Sin esto, alternar entre pestanas apila entradas sin
                                // fin y el boton atras obliga a recorrerlas todas.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            val badgeCount =
                                if (destination == AvesDestination.REVIEW) pendingCount else 0
                            BadgedBox(
                                badge = {
                                    if (badgeCount > 0) {
                                        Badge { Text(badgeCount.coerceAtMost(99).toString()) }
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = destination.icon,
                                    // La etiqueta ya dice que es; el lector de
                                    // pantalla necesita ademas el recuento.
                                    contentDescription = if (badgeCount > 0) {
                                        pluralStringResource(R.plurals.review_pending_badge, badgeCount, badgeCount)
                                    } else {
                                        null
                                    },
                                )
                            }
                        },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        AvesNavHost(
            container = container,
            navController = navController,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            startListeningToken = startListeningToken,
            onStartListeningHandled = onStartListeningHandled,
        )
    }
}
