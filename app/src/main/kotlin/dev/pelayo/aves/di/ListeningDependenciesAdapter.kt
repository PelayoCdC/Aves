package dev.pelayo.aves.di

import android.content.Context
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.RegionRepository
import dev.pelayo.aves.core.listening.ListeningDependencies
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier
import dev.pelayo.aves.core.model.Coordinates
import dev.pelayo.aves.ml.createAudioClassifier
import dev.pelayo.aves.ml.createContextPrior
import java.io.File

/**
 * Conecta el servicio de escucha con el contenedor de la aplicacion.
 *
 * Existe porque un `Service` lo instancia el sistema y no admite inyeccion por
 * constructor. `:core:listening` define QUE necesita, y :app —que es quien sabe
 * que flavor se ha compilado— decide COMO se satisface.
 *
 * El clasificador se crea nuevo en cada escucha y lo cierra el servicio: retiene
 * un modelo mapeado en memoria y no debe sobrevivir a la sesion.
 */
class ListeningDependenciesAdapter(
    private val context: Context,
    private val container: AppContainer,
) : ListeningDependencies {

    override fun createClassifier(): AudioBirdClassifier? {
        val prior = createContextPrior(context, container.regionRepository)
        return createAudioClassifier(context, prior)
    }

    override val observationRepository: ObservationRepository?
        get() = container.observationRepository

    override val regionRepository: RegionRepository?
        get() = container.regionRepository

    override val clipsDirectory: File?
        get() = container.clipsDirectory

    override fun currentLocation(): Coordinates? = container.locationProvider.lastKnown()
}
