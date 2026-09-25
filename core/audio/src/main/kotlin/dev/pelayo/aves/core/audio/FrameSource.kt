package dev.pelayo.aves.core.audio

import kotlinx.coroutines.flow.Flow

/**
 * De donde salen las ventanas de audio que se clasifican.
 *
 * En la app es siempre el microfono ([AudioCaptureEngine]). La interfaz existe
 * para poder probar el bucle de escucha en la JVM con ventanas fabricadas: sin
 * ella, cada comprobacion del motor (la puerta de energia, el descarte mientras
 * suena un recorte, que un fallo del modelo pare la escucha de forma ordenada)
 * exigia un movil y un pajaro.
 */
interface FrameSource {
    /** Muestras nuevas entre dos ventanas consecutivas. */
    val hopSampleCount: Int

    /** Fuente de microfono en uso, para diagnosticar. Null si no aplica. */
    val activeSourceName: String?

    /** El sistema ha cedido el microfono a otra app y llegan ceros. */
    val isSilenced: Boolean

    /** Una ventana cada salto, hasta que se cancele quien recolecta. */
    fun frames(): Flow<AudioFrame>
}
