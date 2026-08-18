package dev.pelayo.aves.ml

import android.content.Context
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier
import dev.pelayo.aves.core.ml.api.ContextPriorProvider
import dev.pelayo.aves.core.ml.birdnet.BirdNetAudioClassifier
import dev.pelayo.aves.core.ml.birdnet.BirdNetContextPriorProvider

/**
 * Variante `personal`: usa BirdNET, con 6.419 especies y licencia CC BY-NC-SA.
 *
 * Existen funciones homonimas en el conjunto de fuentes `open`. Solo se compila
 * uno de los dos segun el flavor, asi que la eleccion de modelo no cuesta ni una
 * comprobacion en tiempo de ejecucion.
 */
fun createAudioClassifier(
    context: Context,
    priorProvider: ContextPriorProvider?,
): AudioBirdClassifier =
    BirdNetAudioClassifier(context.applicationContext, priorProvider = priorProvider)

/**
 * El meta-modelo geo-temporal de BirdNET.
 *
 * Se prefiere al prior derivado de GBIF porque se entreno JUNTO al clasificador:
 * sus probabilidades estan en la misma escala que las predicciones que va a
 * filtrar. El de GBIF es una estimacion externa, buena pero ajena.
 *
 * @param gbifPrior respaldo comun a los dos flavors. Aqui no se usa, pero la
 *   firma se mantiene identica para que el codigo compartido no tenga que saber
 *   cual esta compilado.
 */
@Suppress("UNUSED_PARAMETER")
fun createContextPrior(
    context: Context,
    gbifPrior: ContextPriorProvider,
): ContextPriorProvider = BirdNetContextPriorProvider(context.applicationContext)
