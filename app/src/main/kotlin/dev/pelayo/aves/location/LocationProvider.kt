package dev.pelayo.aves.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dev.pelayo.aves.core.model.Coordinates
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Obtiene la ubicacion aproximada del usuario.
 *
 * Usa `LocationManager` del sistema y no los servicios de Google a proposito:
 * anadir Play Services por una coordenada ataria el flavor publicable a una
 * dependencia propietaria, y aqui no hace ninguna falta la precision de metros.
 * La celda de contexto mide 40x20 km.
 *
 * POR QUE SE PIDE UNA LECTURA FRESCA
 * La primera version solo leia la "ultima conocida", con el argumento de que
 * encender el GPS para un pajaro era desproporcionado. El argumento era bueno
 * y el resultado malo: en un movil moderno esa lectura suele ser null si
 * ninguna app ha pedido ubicacion hace poco, y sin ubicacion no hay prior, ni
 * rareza, ni celda. Una sola lectura de red (barata, sin GPS) al empezar la
 * escucha cuesta un segundo y arregla las tres cosas. Si tarda mas de la
 * cuenta, se cae a la ultima conocida.
 */
class LocationProvider(private val context: Context) {

    fun hasPermission(): Boolean = COARSE_AND_FINE.any { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Una lectura fresca si el sistema la da a tiempo; la ultima conocida si
     * no. Null si no hay permiso o no hay nada: sin ubicacion la app identifica
     * igual, solo que sin filtro de contexto.
     */
    suspend fun current(timeoutMillis: Long = FRESH_FIX_TIMEOUT_MILLIS): Coordinates? {
        if (!hasPermission()) return null
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java)
            ?: return null

        val fresh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                withTimeoutOrNull(timeoutMillis) { requestFresh(manager) }
            }.onFailure { Log.w(TAG, "No se pudo pedir una ubicacion fresca", it) }.getOrNull()
        } else {
            null
        }
        return fresh ?: lastKnown()
    }

    /**
     * @return la ultima ubicacion conocida, o null si no hay permiso, no hay
     *   proveedores activos o el sistema aun no tiene ninguna.
     */
    fun lastKnown(): Coordinates? {
        if (!hasPermission()) return null

        val manager = ContextCompat.getSystemService(context, LocationManager::class.java)
            ?: return null

        return try {
            // Se recorren los proveedores de menos a mas costoso y se toma la
            // primera lectura utilizable.
            PROVIDERS
                .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
                .firstNotNullOfOrNull { provider ->
                    @Suppress("MissingPermission")
                    manager.getLastKnownLocation(provider)
                }
                ?.toCoordinates()
        } catch (e: SecurityException) {
            // El permiso puede revocarse entre la comprobacion y la lectura.
            Log.w(TAG, "Permiso de ubicacion revocado mientras se leia", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun requestFresh(manager: LocationManager): Coordinates? =
        suspendCancellableCoroutine { continuation ->
            // Red primero: no enciende el GPS y su precision sobra para una
            // celda de 40 km.
            val provider = PROVIDERS.firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            if (provider == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                if (continuation.isActive) continuation.resume(location?.toCoordinates())
            }
        }

    private fun Location.toCoordinates() = Coordinates(latitude, longitude)

    private companion object {
        const val TAG = "LocationProvider"

        /** Lo que se espera a una lectura de red antes de conformarse con la ultima conocida. */
        const val FRESH_FIX_TIMEOUT_MILLIS = 8_000L

        val COARSE_AND_FINE = listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        /** NETWORK primero: es el barato y su precision sobra para una celda de 40 km. */
        val PROVIDERS = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
    }
}
