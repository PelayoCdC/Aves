package dev.pelayo.aves.core.ml.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Una sola inferencia a la vez, y el modelo nativo se libera cuando ninguna lo
 * esta usando.
 *
 * Existe por un fallo concreto: `close()` no es `suspend` (lo exige
 * [java.io.Closeable]) y se llamaba desde `onCleared` justo despues de
 * cancelar el analisis. La cancelacion solo surte efecto en el siguiente punto
 * de suspension, asi que el interprete de LiteRT o la sesion de ONNX se
 * liberaban con un `run()` todavia en marcha en otro hilo: un fallo nativo, no
 * una excepcion.
 *
 * Con esta puerta, [close] marca el modelo como cerrado y lo libera en el acto
 * solo si nadie lo tiene; si hay una inferencia en curso, lo libera ella al
 * terminar. Lo que llegue despues falla con [IllegalStateException], que es un
 * error normal y no un `SIGSEGV`.
 *
 * @param release suelta los recursos nativos. Se llama como mucho una vez.
 */
class ModelGate(private val release: () -> Unit) {

    private val mutex = Mutex()
    private val released = AtomicBoolean(false)

    @Volatile
    var isClosed: Boolean = false
        private set

    /** Ejecuta [block] con el modelo en exclusiva, o falla si ya se ha cerrado. */
    suspend fun <T> use(block: suspend () -> T): T = mutex.withLock {
        check(!isClosed) { "El clasificador ya se ha cerrado" }
        try {
            block()
        } finally {
            // Si close() llego mientras se inferia, no pudo liberar: toca aqui.
            if (isClosed) releaseOnce()
        }
    }

    fun close() {
        // Primero la marca y despues el intento de coger el cerrojo: si quien
        // lo tiene suelta entre medias, ya ve la marca en su `finally`.
        isClosed = true
        if (mutex.tryLock()) {
            try {
                releaseOnce()
            } finally {
                mutex.unlock()
            }
        }
    }

    private fun releaseOnce() {
        if (released.compareAndSet(false, true)) release()
    }
}
