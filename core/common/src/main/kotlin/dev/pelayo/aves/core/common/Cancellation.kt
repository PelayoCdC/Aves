package dev.pelayo.aves.core.common

import kotlin.coroutines.cancellation.CancellationException

/**
 * `runCatching` para codigo que suspende: igual, salvo que la cancelacion NO
 * se atrapa.
 *
 * El `runCatching` de la biblioteca estandar se traga `CancellationException`
 * como cualquier otra, y con ella la orden de parar: una escucha cancelada
 * mientras leia los ajustes seguia adelante y volvia a poner la notificacion
 * de "escuchando" de un servicio ya parado. Es `inline` para que el bloque
 * pueda llamar a funciones `suspend`.
 */
inline fun <T> runSuspendCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
