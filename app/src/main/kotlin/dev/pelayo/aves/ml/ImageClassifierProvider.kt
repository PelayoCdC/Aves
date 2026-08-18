package dev.pelayo.aves.ml

import android.content.Context
import dev.pelayo.aves.core.ml.api.ContextPriorProvider
import dev.pelayo.aves.core.ml.api.ImageBirdClassifier
import dev.pelayo.aves.core.ml.vision.AiyBirdImageClassifier

/**
 * El clasificador de imagen, identico en los dos flavors.
 *
 * No hay variantes por conjunto de fuentes como en el de audio porque aqui no
 * hace falta: el modelo de Google AIY es **Apache 2.0**, asi que puede viajar
 * tambien en el APK publicable. Es la diferencia entre depender de un modelo
 * restringido y depender de uno libre.
 */
fun createImageClassifier(
    context: Context,
    priorProvider: ContextPriorProvider?,
): ImageBirdClassifier =
    AiyBirdImageClassifier(context.applicationContext, priorProvider = priorProvider)
