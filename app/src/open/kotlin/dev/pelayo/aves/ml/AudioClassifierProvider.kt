package dev.pelayo.aves.ml

import android.content.Context
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier
import dev.pelayo.aves.core.ml.api.ContextPriorProvider

/**
 * Variante `open`: todavia no hay clasificador de audio publicable.
 *
 * No es un descuido, es el estado real del asunto. Perch (Google) es Apache 2.0
 * y seria el sustituto natural, pero no existe un TFLite suyo publicado: solo
 * SavedModel. Convertirlo y entrenar una cabeza regional propia es la Fase 7 y
 * es un proyecto en si mismo.
 */
@Suppress("UNUSED_PARAMETER")
fun createAudioClassifier(
    context: Context,
    priorProvider: ContextPriorProvider?,
): AudioBirdClassifier? = null

/**
 * En el flavor publicable el prior sale de GBIF, que no tiene clausula no
 * comercial. Es menos fino que el meta-modelo de BirdNET, pero es el unico que
 * se puede distribuir.
 */
@Suppress("UNUSED_PARAMETER")
fun createContextPrior(
    context: Context,
    gbifPrior: ContextPriorProvider,
): ContextPriorProvider = gbifPrior
