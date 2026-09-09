package dev.pelayo.aves

import android.app.Application
import android.util.Log
import dev.pelayo.aves.core.listening.ListeningService
import dev.pelayo.aves.di.AppContainer
import dev.pelayo.aves.di.ListeningDependenciesAdapter
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AvesApplication : Application() {

    lateinit var container: AppContainer
        private set

    /**
     * Ambito de trabajos que sobreviven a cualquier pantalla. Con SupervisorJob
     * para que un fallo en uno no derribe los demas.
     */
    private val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, error -> Log.e(TAG, "Fallo en tarea de fondo", error) },
    )

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, applicationScope)

        // El servicio de escucha no puede recibir sus dependencias por
        // constructor: lo instancia el sistema. Este punto de inyeccion estatico
        // desaparece cuando entre Hilt.
        ListeningService.dependencies = ListeningDependenciesAdapter(this, container)

        // El catalogo se siembra en segundo plano nada mas arrancar, no la
        // primera vez que se necesita: asi la espera cae en el arranque, donde
        // el usuario todavia no esta esperando nada concreto, y no en mitad de
        // una identificacion. Despues se rellenan las rarezas que quedaron
        // pendientes por falta de datos en su momento.
        applicationScope.launch {
            runCatching { container.catalogSeeder.seedIfNeeded() }
                .onFailure { Log.e(TAG, "No se pudo sembrar el catalogo", it) }
            container.regionWarmer.fillRarities()
        }
    }

    private companion object {
        const val TAG = "AvesApplication"
    }
}
