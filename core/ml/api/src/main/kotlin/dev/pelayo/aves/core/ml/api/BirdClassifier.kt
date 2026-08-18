package dev.pelayo.aves.core.ml.api

import android.graphics.Bitmap
import java.io.Closeable

/**
 * Clasificador de aves por vocalizacion.
 *
 * El detalle que hace que esta abstraccion sirva de algo es que el formato de
 * audio lo declara el MODELO, no la app. BirdNET quiere 48 kHz y ventanas de
 * 3 s (144.000 muestras); Perch quiere 32 kHz y 5 s (160.000). Si la capa de
 * captura tuviera esos numeros cableados, "cambiar de modelo" acabaria siendo
 * una reescritura, que es exactamente el fallo que este diseno evita.
 *
 * Implementa [Closeable] porque un interprete de LiteRT retiene un
 * MappedByteBuffer de decenas de MB: hay que cerrarlo, no esperar al GC.
 */
interface AudioBirdClassifier : Closeable {

    val descriptor: ModelDescriptor

    /** Frecuencia de muestreo exigida, en Hz. BirdNET 48000, Perch 32000. */
    val requiredSampleRate: Int

    /** Muestras por inferencia. BirdNET 144000 (3 s), Perch 160000 (5 s). */
    val requiredSampleCount: Int

    /** Carga el modelo y hace una inferencia en vacio, para que la primera real no tarde de mas. */
    suspend fun warmUp()

    /**
     * @param samples exactamente [requiredSampleCount] muestras mono a [requiredSampleRate].
     * @return candidatos ordenados de mayor a menor [ClassificationResult.adjustedScore].
     */
    suspend fun classify(
        samples: FloatArray,
        context: ObservationContext,
    ): List<ClassificationResult>
}

/**
 * Clasificador de aves por imagen.
 *
 * Comparte [ObservationContext] y [ClassificationResult] con el de audio a
 * proposito: todo lo que viene despues de la inferencia (mapeo de taxonomias,
 * prior geo-temporal, persistencia) se reutiliza tal cual.
 */
interface ImageBirdClassifier : Closeable {

    val descriptor: ModelDescriptor

    /** Lado del cuadrado de entrada en pixeles. 224 para MobileNet V2. */
    val inputSize: Int

    suspend fun warmUp()

    suspend fun classify(
        bitmap: Bitmap,
        context: ObservationContext,
    ): List<ClassificationResult>
}
