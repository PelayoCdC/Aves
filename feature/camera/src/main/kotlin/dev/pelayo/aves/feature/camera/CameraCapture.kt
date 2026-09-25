package dev.pelayo.aves.feature.camera

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Espera al proveedor de camara del proceso.
 *
 * `getInstance` devuelve un ListenableFuture y no una corrutina; envolverlo aqui
 * mantiene el resto de la pantalla en el mismo estilo suspendido que el de
 * audio.
 */
internal suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                // runCatching y no try/resume: si `resume` quedara dentro del
                // try, un fallo suyo intentaria reanudar dos veces la misma
                // continuacion.
                val result = runCatching { future.get() }
                result.onSuccess { if (continuation.isActive) continuation.resume(it) }
                result.onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
            },
            ContextCompat.getMainExecutor(this),
        )
        continuation.invokeOnCancellation { future.cancel(false) }
    }

/**
 * Dispara y devuelve la foto en sus dos tamanos: la entera para guardarla y el
 * recorte cuadrado de [targetSize] pixeles para el modelo.
 *
 * Toda la conversion ocurre dentro del callback, en el hilo de [executor], para
 * que el [ImageProxy] se cierre en el mismo `finally` que lo abrio. CameraX
 * trabaja con un numero fijo de buffers: una sola ruta de salida que no cierre
 * y la camara deja de disparar tras unas pocas fotos, sin error visible.
 */
internal suspend fun ImageCapture.capturePhoto(
    executor: Executor,
    targetSize: Int,
): CapturedPhoto = suspendCancellableCoroutine { continuation ->
    takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val prepared = image.toCapturedPhoto(targetSize)
                    if (continuation.isActive) continuation.resume(prepared)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                if (continuation.isActive) continuation.resumeWithException(exception)
            }
        },
    )
}

/**
 * Saca el JPEG del plano 0 y lo convierte en la foto y en la entrada del modelo.
 *
 * `OnImageCapturedCallback` entrega JPEG por defecto, no YUV: el plano 0 no es
 * un plano de luminancia sino el fichero comprimido entero, y hay que
 * decodificarlo.
 */
internal fun ImageProxy.toCapturedPhoto(targetSize: Int): CapturedPhoto {
    val buffer = planes[0].buffer
    // El buffer puede llegar con la posicion consumida si alguien lo miro antes.
    buffer.rewind()
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return decodeCapturedPhoto(bytes, imageInfo.rotationDegrees, targetSize)
}
