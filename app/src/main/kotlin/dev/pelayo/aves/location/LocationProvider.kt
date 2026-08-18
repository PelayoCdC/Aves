package dev.pelayo.aves.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.pelayo.aves.core.model.Coordinates

/**
 * Obtiene la ubicacion aproximada del usuario.
 *
 * Usa `LocationManager` del sistema y no los servicios de Google a proposito:
 * anadir Play Services por una coordenada ataria el flavor publicable a una
 * dependencia propietaria, y aqui no hace ninguna falta la precision de metros.
 * La celda de contexto mide 40x20 km.
 *
 * Solo lee la ULTIMA ubicacion conocida, sin pedir uno nuevo. Encender el GPS
 * para identificar un pajaro seria desproporcionado: gasta bateria, tarda
 * segundos que el canto no espera, y la precision que se gana es irrelevante a
 * escala de celda.
 */
class LocationProvider(private val context: Context) {

    fun hasPermission(): Boolean = COARSE_AND_FINE.any { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * @return la ultima ubicacion conocida, o null si no hay permiso, no hay
     *   proveedores activos o el sistema aun no tiene ninguna. Devolver null es
     *   un resultado legitimo: sin ubicacion la app identifica igual, solo que
     *   sin filtro de contexto.
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
                ?.let { Coordinates(it.latitude, it.longitude) }
        } catch (e: SecurityException) {
            // El permiso puede revocarse entre la comprobacion y la lectura.
            Log.w(TAG, "Permiso de ubicacion revocado mientras se leia", e)
            null
        }
    }

    private companion object {
        const val TAG = "LocationProvider"

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
