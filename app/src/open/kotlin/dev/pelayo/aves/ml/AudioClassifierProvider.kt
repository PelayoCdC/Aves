package dev.pelayo.aves.ml

import android.content.Context
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier
import dev.pelayo.aves.core.ml.api.ContextPriorProvider
import dev.pelayo.aves.core.ml.api.FallbackPriorProvider
import dev.pelayo.aves.core.ml.birdnetplus.BirdNetPlusAudioClassifier
import dev.pelayo.aves.core.ml.birdnetplus.BirdNetPlusGeoPriorProvider

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
 * El geomodelo de BirdNET+, tambien Apache 2.0, con GBIF de respaldo.
 *
 * Se prefiere al prior de GBIF por la misma razon que el MData en el flavor
 * personal: se entreno junto al clasificador y sus probabilidades estan
 * pensadas para filtrar exactamente esas predicciones. Si el fichero faltara o
 * fallara al cargar, se cae a GBIF en vez de escuchar sin contexto.
 */
fun createContextPrior(
    context: Context,
    gbifPrior: ContextPriorProvider,
): ContextPriorProvider = FallbackPriorProvider(
    primary = BirdNetPlusGeoPriorProvider(context.applicationContext),
    fallback = gbifPrior,
)
