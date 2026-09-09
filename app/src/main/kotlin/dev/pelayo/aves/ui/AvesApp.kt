package dev.pelayo.aves.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.pelayo.aves.di.AppContainer
import dev.pelayo.aves.navigation.AvesDestination
import dev.pelayo.aves.navigation.AvesNavHost
import kotlinx.coroutines.flow.map

@Composable
fun AvesApp(container: AppContainer, startListeningToken: Int = 0) {
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
    val currentDestination = backStackEntry?.destination

    // El contador de pendientes en la pestaña no es decoracion: sin el, la
    // bandeja de revision se olvida, las detecciones se quedan sin confirmar y
    // la coleccion nunca crece aunque la app este identificando bien.
    val pendingFlow = remember(container) {
        container.observationRepository.pendingReview().map { it.size }
    }
    val pendingCount by pendingFlow.collectAsStateWithLifecycle(initialValue = 0)

    Scaffold(
        bottomBar = {
            NavigationBar {
                AvesDestination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy
                        ?.any { it.route == destination.route } == true

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
                                    contentDescription = null,
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
        )
    }
}
