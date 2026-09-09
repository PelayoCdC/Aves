package dev.pelayo.aves

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.pelayo.aves.core.designsystem.theme.AvesTheme
import dev.pelayo.aves.core.listening.ListeningTileService
import dev.pelayo.aves.ui.AvesApp

class MainActivity : ComponentActivity() {

    /**
     * Cuantas veces se ha pedido arrancar la escucha desde fuera (el tile de
     * ajustes rapidos). Es un contador y no un booleano para que dos peticiones
     * seguidas se vean como dos cambios de estado.
     */
    private var startListeningToken by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        consumeStartRequest(intent)
        val container = (application as AvesApplication).container
        setContent {
            AvesTheme {
                AvesApp(container, startListeningToken = startListeningToken)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: el tile llega aqui si la app ya estaba abierta.
        consumeStartRequest(intent)
    }

    private fun consumeStartRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(ListeningTileService.EXTRA_START_LISTENING, false) == true) {
            intent.removeExtra(ListeningTileService.EXTRA_START_LISTENING)
            startListeningToken++
        }
    }
}
