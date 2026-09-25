package dev.pelayo.aves

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pelayo.aves.core.designsystem.theme.AvesSkin
import dev.pelayo.aves.core.designsystem.theme.AvesTheme
import dev.pelayo.aves.feature.settings.AvesSettings
import dev.pelayo.aves.core.listening.ListeningTileService
import dev.pelayo.aves.ui.AvesApp

class MainActivity : ComponentActivity() {

    /**
     * Peticion pendiente de arrancar la escucha desde fuera (el tile de ajustes
     * rapidos). Es un contador para que dos peticiones seguidas se vean como
     * dos cambios de estado, y vuelve a 0 en cuanto la pantalla de identificar
     * la atiende: si se quedaba en 1, volver a esa pestana tras parar la
     * escucha la arrancaba otra vez.
     */
    private var startListeningToken by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Solo en el primer arranque: al recrear la actividad (girar el movil,
        // o volver tras morir el proceso) el sistema entrega OTRA VEZ el intent
        // original, con el extra puesto, y la escucha que el usuario habia
        // parado volvia a arrancar sola.
        if (savedInstanceState == null) consumeStartRequest(intent)
        val container = (application as AvesApplication).container
        setContent {
            // La piel la elige el usuario en Ajustes. Hasta la primera lectura
            // del disco, la del sistema: es la que menos desentona un instante.
            val settings by container.settingsStore.settings.collectAsStateWithLifecycle(initialValue = AvesSettings())
            AvesTheme(skin = AvesSkin.fromName(settings.skin)) {
                AvesApp(
                    container,
                    startListeningToken = startListeningToken,
                    onStartListeningHandled = { startListeningToken = 0 },
                )
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
