package dev.pelayo.aves

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.pelayo.aves.core.map.MapSetup
import dev.pelayo.aves.core.network.WikipediaClient
import dev.pelayo.aves.core.listening.ListeningService
import dev.pelayo.aves.di.AppContainer
import dev.pelayo.aves.di.ListeningDependenciesAdapter
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class AvesApplication : Application(), SingletonImageLoader.Factory {

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

        // El mapa: User-Agent de la app para los servidores de teselas y cache
        // en la cache de la app, con tope.
        MapSetup.configure(this, WikipediaClient.USER_AGENT)

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

    /**
     * El cargador de imagenes de toda la app, con un User-Agent que identifica a
     * la app (nunca al usuario).
     *
     * Sin esto las fotos de Wikipedia NO CARGAN: Wikimedia responde 403 al
     * "okhttp/x.y" que manda OkHttp por defecto, y Coil pinta un hueco sin dar
     * ningun error visible. El texto de la ficha si llegaba porque
     * [WikipediaClient] ya mandaba el suyo; las imagenes van por otro cliente.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", WikipediaClient.USER_AGENT)
                        .build(),
                )
            }
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()
    }

    private companion object {
        const val TAG = "AvesApplication"
    }
}
