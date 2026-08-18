package dev.pelayo.aves

import android.app.Application
import android.util.Log
import dev.pelayo.aves.di.AppContainer
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
        container = AppContainer(this)

        // El catalogo se siembra en segundo plano nada mas arrancar, no la
        // primera vez que se necesita: asi la espera cae en el arranque, donde
        // el usuario todavia no esta esperando nada concreto, y no en mitad de
        // una identificacion.
        applicationScope.launch {
            runCatching { container.catalogSeeder.seedIfNeeded() }
                .onFailure { Log.e(TAG, "No se pudo sembrar el catalogo", it) }
        }
    }

    private companion object {
        const val TAG = "AvesApplication"
    }
}
