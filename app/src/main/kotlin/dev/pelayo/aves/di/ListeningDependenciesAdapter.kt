package dev.pelayo.aves.di

import android.content.Context
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.RegionRepository
import dev.pelayo.aves.core.listening.DetectionNamer
import dev.pelayo.aves.core.listening.ListeningDeadline
import dev.pelayo.aves.core.listening.ListeningDependencies
import dev.pelayo.aves.core.listening.ListeningSettings
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier
import dev.pelayo.aves.core.ml.api.AudioModelChoice
import dev.pelayo.aves.core.model.Coordinates
import dev.pelayo.aves.feature.settings.AvesSettings
import dev.pelayo.aves.ml.createAudioClassifier
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Conecta el servicio de escucha con el contenedor de la aplicacion.
 *
 * Existe porque un `Service` lo instancia el sistema y no admite inyeccion por
 * constructor. `:core:listening` define QUE necesita, y :app —que es quien
 * conoce los modelos concretos— decide COMO se satisface.
 *
 * El clasificador se crea nuevo en cada escucha y lo cierra el servicio: retiene
 * un modelo mapeado en memoria y no debe sobrevivir a la sesion. El prior, en
 * cambio, es el singleton del contenedor: su cache tiene que sobrevivir.
 */
class ListeningDependenciesAdapter(
    private val context: Context,
    private val container: AppContainer,
) : ListeningDependencies {

    override fun createClassifier(settings: ListeningSettings): AudioBirdClassifier? =
        createAudioClassifier(context, settings.audioModel, container.audioPriors, container.speciesResolver::resolve)

    override val observationRepository: ObservationRepository?
        get() = container.observationRepository

    override val regionRepository: RegionRepository?
        get() = container.regionRepository

    override val clipsDirectory: File?
        get() = container.clipsDirectory

    override val detectionNamer: DetectionNamer?
        get() = container.detectionNamer

    override suspend fun currentSettings(): ListeningSettings =
        container.settingsStore.settings.first().toListeningSettings()

    override suspend fun currentLocation(): Coordinates? = container.locationProvider.current()

    override fun warmRegion(location: Coordinates) = container.regionWarmer.warm(location)
}

/**
 * La traduccion entre lo que guarda la pantalla de ajustes y lo que consume la
 * escucha. Es lo que faltaba: los ajustes se escribian y nadie los leia.
 */
fun AvesSettings.toListeningSettings(): ListeningSettings = ListeningSettings(
    contextInfluence = contextInfluence,
    confirmThreshold = confidenceThreshold,
    maxDurationMillis = if (listeningCutoffMinutes == AvesSettings.NO_CUTOFF) {
        // "Sin corte" es el tope de seguridad del servicio, no el infinito.
        ListeningDeadline.MAX_DURATION_MILLIS
    } else {
        listeningCutoffMinutes * 60_000L
    },
    useLocation = !locationDisabled,
    audioModel = AudioModelChoice.fromName(audioModel),
)
