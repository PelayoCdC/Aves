package dev.pelayo.aves.ml

import android.content.Context
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier
import dev.pelayo.aves.core.ml.api.ContextPriorProvider
import dev.pelayo.aves.core.ml.birdnetplus.BirdNetPlusAudioClassifier

/**
 * Variante `open`: BirdNET+ V3, con pesos **Apache 2.0**.
 *
 * Durante mucho tiempo este flavor no tuvo clasificador de audio, porque los
 * pesos de BirdNET v2.4 son CC BY-NC-SA y no se pueden distribuir. El plan
 * preveia convertir Perch y entrenar una cabeza propia: semanas de trabajo con
 * riesgo real de no salir.
 *
 * No hizo falta. El propio equipo de BirdNET publica pesos bajo Apache 2.0 en
 * `birdnet-team/birdnet-live-app`, y su `MODEL_LICENSE` lo dice literalmente.
 * Ademas BirdNET+ cubre 8.923 especies de ave frente a las 6.421 de la v2.4:
 * el flavor publicable acaba siendo MEJOR que el personal.
 *
 * Es tambien la prueba de fuego de la arquitectura. BirdNET+ trabaja a 32 kHz y
 * no a 48, y usa ONNX en vez de TFLite. Nada de eso obligo a tocar `:core:audio`
 * ni las pantallas: el modelo declara su formato y el resto se adapta.
 */
fun createAudioClassifier(
    context: Context,
    priorProvider: ContextPriorProvider?,
): AudioBirdClassifier =
    BirdNetPlusAudioClassifier(context.applicationContext, priorProvider = priorProvider)

/**
 * En el flavor publicable el prior sale de GBIF, que no tiene clausula no
 * comercial.
 *
 * BirdNET+ trae ademas su propio geomodelo, tambien Apache 2.0 y ya descargado
 * en assets. Integrarlo es el siguiente paso natural: seria un prior mejor que
 * el de GBIF por la misma razon que el MData lo es en el flavor personal, porque
 * se entreno junto al clasificador.
 */
@Suppress("UNUSED_PARAMETER")
fun createContextPrior(
    context: Context,
    gbifPrior: ContextPriorProvider,
): ContextPriorProvider = gbifPrior
