package dev.pelayo.aves.ml

import android.content.Context
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier
import dev.pelayo.aves.core.ml.birdnet.BirdNetAudioClassifier

/**
 * Variante `personal`: usa BirdNET, con 6.515 especies y licencia CC BY-NC-SA.
 *
 * Existe una funcion homonima en el conjunto de fuentes `open` que devuelve
 * null. Solo se compila una de las dos segun el flavor, asi que la eleccion de
 * modelo no cuesta ni una comprobacion en tiempo de ejecucion.
 */
fun createAudioClassifier(context: Context): AudioBirdClassifier =
    BirdNetAudioClassifier(context.applicationContext)
