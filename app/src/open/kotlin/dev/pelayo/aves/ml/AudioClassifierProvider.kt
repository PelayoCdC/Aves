package dev.pelayo.aves.ml

import android.content.Context
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier

/**
 * Variante `open`: todavia no hay clasificador de audio publicable.
 *
 * No es un descuido, es el estado real del asunto. Perch (Google) es Apache 2.0
 * y seria el sustituto natural, pero no existe un TFLite suyo publicado: solo
 * SavedModel. Convertirlo y entrenar una cabeza regional propia es la Fase 7 y
 * es un proyecto en si mismo.
 *
 * Devolver null aqui es lo correcto: la interfaz lo explica al usuario en vez de
 * fingir que funciona.
 */
@Suppress("UNUSED_PARAMETER")
fun createAudioClassifier(context: Context): AudioBirdClassifier? = null
