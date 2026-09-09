package dev.pelayo.aves.core.ml.api

import android.util.Log
import kotlin.coroutines.cancellation.CancellationException

/**
 * Un prior con red de seguridad.
 *
 * El geomodelo de cada clasificador es el prior preferido porque se entreno
 * junto a el, pero es un fichero mas que puede faltar o fallar al cargar. Si
 * falla, se cae al prior de respaldo (el derivado de GBIF) en vez de dejar la
 * escucha sin contexto, y se deja de intentar el primario para no pagar el
 * fallo en cada ventana.
 */
class FallbackPriorProvider(
    private val primary: ContextPriorProvider,
    private val fallback: ContextPriorProvider,
) : ContextPriorProvider {

    @Volatile
    private var primaryBroken = false

    override suspend fun prior(context: ObservationContext): SpeciesPrior {
        if (!primaryBroken) {
            try {
                return primary.prior(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                primaryBroken = true
                Log.w(TAG, "El prior principal fallo; se usa el de respaldo", e)
            }
        }
        return fallback.prior(context)
    }

    private companion object {
        const val TAG = "FallbackPrior"
    }
}
