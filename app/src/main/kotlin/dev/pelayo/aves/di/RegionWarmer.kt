package dev.pelayo.aves.di

import android.util.Log
import dev.pelayo.aves.core.common.Geohash
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.RegionRepository
import dev.pelayo.aves.core.model.Coordinates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Descarga los datos de ocurrencia de una celda y rellena las rarezas que
 * quedaron pendientes, en un ambito que NO muere con la escucha.
 *
 * Antes la descarga vivia dentro del bucle de escucha y se cancelaba con el:
 * doce llamadas a GBIF que solo se escribian al terminar las doce, y una
 * salida corta con mala cobertura no dejaba nada. Ahora el mes actual se pide
 * primero y se guarda solo, el resto llega cuando llega, y cada vez que entra
 * un mes se recalculan las observaciones que estaban esperando esos datos.
 */
class RegionWarmer(
    private val scope: CoroutineScope,
    private val regionRepository: RegionRepository,
    private val observationRepository: ObservationRepository,
) {
    private val inFlight = HashSet<String>()

    fun warm(location: Coordinates) {
        val cellId = Geohash.encode(location.latitude, location.longitude, RegionRepository.CELL_PRECISION)
        synchronized(inFlight) {
            if (!inFlight.add(cellId)) return
        }

        scope.launch {
            try {
                val now = System.currentTimeMillis()
                // Primero lo urgente: el mes en el que se esta escuchando.
                if (regionRepository.ensureCurrentMonthCached(location.latitude, location.longitude, now)) {
                    fillRarities()
                }
                // Y despues el resto del ano, para que la siguiente salida no
                // dependa de tener cobertura.
                val months = regionRepository.ensureCellCached(location.latitude, location.longitude, now)
                if (months > 0) fillRarities()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Sin red no hay rareza, pero hay escucha. Se reintenta en la
                // siguiente sesion.
                Log.w(TAG, "No se pudo cachear la celda $cellId", e)
            } finally {
                synchronized(inFlight) { inFlight.remove(cellId) }
            }
        }
    }

    /** Rellena las observaciones con rareza pendiente que ya tengan datos. */
    suspend fun fillRarities() {
        val updated = runCatching { observationRepository.fillUnknownRarities(regionRepository) }
            .onFailure { Log.w(TAG, "No se pudieron rellenar las rarezas", it) }
            .getOrDefault(0)
        if (updated > 0) Log.i(TAG, "Rarezas rellenadas: $updated observaciones")
    }

    private companion object {
        const val TAG = "RegionWarmer"
    }
}
