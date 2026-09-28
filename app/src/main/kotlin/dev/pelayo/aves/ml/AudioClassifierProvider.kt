package dev.pelayo.aves.ml

import android.content.Context
import dev.pelayo.aves.core.listening.ListeningService
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier
import dev.pelayo.aves.core.ml.api.AudioModelChoice
import dev.pelayo.aves.core.ml.api.ContextPriorProvider
import dev.pelayo.aves.core.ml.api.FallbackPriorProvider
import dev.pelayo.aves.core.ml.birdnet.BirdNetAudioClassifier
import dev.pelayo.aves.core.ml.birdnet.BirdNetContextPriorProvider
import dev.pelayo.aves.core.ml.birdnetplus.BirdNetPlusAudioClassifier
import dev.pelayo.aves.core.ml.birdnetplus.BirdNetPlusGeoPriorProvider

/**
 * El prior geo-temporal de cada modelo. Cada uno usa el que se entreno con el
 * (el meta-modelo MData con v2.4, el geomodelo con BirdNET+): sus
 * probabilidades estan en la misma escala que las predicciones que filtran. Si
 * el suyo falta o falla, los dos caen al de GBIF.
 *
 * Son singletons del contenedor: el barrido anual del geomodelo es caro y su
 * cache tiene que sobrevivir entre escuchas.
 */
class AudioPriors(
    val birdnet: ContextPriorProvider,
    val birdnetPlus: ContextPriorProvider,
)

fun createAudioPriors(context: Context, gbifPrior: ContextPriorProvider): AudioPriors = AudioPriors(
    birdnet = FallbackPriorProvider(
        primary = BirdNetContextPriorProvider(context.applicationContext),
        fallback = gbifPrior,
    ),
    birdnetPlus = FallbackPriorProvider(
        primary = BirdNetPlusGeoPriorProvider(context.applicationContext),
        fallback = gbifPrior,
    ),
)

/**
 * El clasificador de cantos que ha elegido el usuario en Ajustes.
 *
 * La app lleva los dos modelos: BirdNET v2.4 (CC BY-NC-SA, no comercial) y
 * BirdNET+ V3 (Apache 2.0). Con v2.4 dentro la app no se puede publicar ni
 * vender, y a cambio lo tiene todo. Si algun dia se quiere publicar, basta con
 * sacar `:core:ml:birdnet` del build y esta funcion.
 *
 * @param resolve (modelo, indice) -> especie canonica. Solo lo usa la escucha
 *   con los dos a la vez, para juntar sus resultados.
 */
fun createAudioClassifier(
    context: Context,
    choice: AudioModelChoice,
    priors: AudioPriors,
    resolve: suspend (modelId: String, labelIndex: Int) -> String?,
): AudioBirdClassifier {
    val appContext = context.applicationContext
    return when (choice) {
        AudioModelChoice.BIRDNET -> BirdNetAudioClassifier(appContext, priorProvider = priors.birdnet)
        AudioModelChoice.BIRDNET_PLUS -> BirdNetPlusAudioClassifier(appContext, priorProvider = priors.birdnetPlus)
        AudioModelChoice.BOTH -> CombinedAudioClassifier(
            first = BirdNetAudioClassifier(appContext, priorProvider = priors.birdnet),
            second = BirdNetPlusAudioClassifier(appContext, priorProvider = priors.birdnetPlus),
            resolve = resolve,
            // La misma escala con la que capturan la escucha y los ficheros.
            scaling = ListeningService.INPUT_SCALING,
        )
    }
}
