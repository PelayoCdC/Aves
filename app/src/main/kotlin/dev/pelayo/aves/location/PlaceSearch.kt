package dev.pelayo.aves.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import dev.pelayo.aves.core.designsystem.components.PickedPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * "Doñana" -> unas coordenadas, con el geocodificador del sistema.
 *
 * Sin claves ni SDK de mapas: `Geocoder` viene con Android. A cambio no esta en
 * todos los dispositivos (los que no llevan servicios de Google pueden no
 * tenerlo) y necesita red. Cuando no hay, devuelve una lista vacia y el selector
 * ofrece escribir las coordenadas a mano.
 *
 * Privacidad: lo unico que sale del movil es el texto que el usuario ha escrito
 * para buscar, hacia el servicio de geocodificacion del sistema.
 */
class PlaceSearch(context: Context) {

    // En castellano para que "Doñana" vuelva como "Parque Nacional de Doñana" y
    // no como "Doñana National Park".
    private val geocoder = Geocoder(context.applicationContext, Locale("es"))

    suspend fun search(query: String): List<PickedPlace> {
        val text = query.trim()
        if (text.isEmpty() || !Geocoder.isPresent()) return emptyList()
        return try {
            lookUp(text).mapNotNull(::toPlace).distinctBy { it.name }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sin red, o el servicio no contesta: para el usuario es "no se ha
            // encontrado", que es lo que dice el selector.
            Log.w(TAG, "El geocodificador no respondio", e)
            emptyList()
        }
    }

    private suspend fun lookUp(text: String): List<Address> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocationName(
                    text,
                    MAX_RESULTS,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses)
                        }

                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(emptyList())
                        }
                    },
                )
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(text, MAX_RESULTS).orEmpty()
            }
        }

    private fun toPlace(address: Address): PickedPlace? {
        if (!address.hasLatitude() || !address.hasLongitude()) return null
        return PickedPlace(
            latitude = address.latitude,
            longitude = address.longitude,
            name = placeName(
                feature = address.featureName,
                locality = address.locality,
                province = address.subAdminArea,
                region = address.adminArea,
                country = address.countryName,
            ),
        )
    }

    private companion object {
        const val TAG = "PlaceSearch"
        const val MAX_RESULTS = 5
    }
}

/**
 * El nombre de un resultado: lo concreto primero y lo justo de contexto para
 * distinguir dos sitios que se llaman igual ("Villanueva, Asturias" y
 * "Villanueva, Badajoz"). Sin repetidos, que el geocodificador da muchos
 * ("Madrid, Madrid, Comunidad de Madrid").
 */
internal fun placeName(
    feature: String?,
    locality: String?,
    province: String?,
    region: String?,
    country: String?,
): String {
    val parts = listOf(feature, locality, province, region, country)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        // Un "nombre" que es solo un numero es el portal de una calle.
        .filterNot { part -> part.all { it.isDigit() } }
    val unique = ArrayList<String>()
    for (part in parts) {
        if (unique.none { it.equals(part, ignoreCase = true) }) unique += part
    }
    return unique.take(3).joinToString(", ").ifEmpty { "Lugar sin nombre" }
}
