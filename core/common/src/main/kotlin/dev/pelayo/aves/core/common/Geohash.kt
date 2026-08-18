package dev.pelayo.aves.core.common

/**
 * Geohash: se usa para trocear el mundo en celdas y cachear por celda los datos
 * de ocurrencia de GBIF.
 *
 * Precision 4 (~40x20 km) es el compromiso elegido: lo bastante fina para que
 * el prior de especies sea util y lo bastante gruesa para que el usuario no
 * salga de la celda al cruzar la ciudad, lo que dispararia descargas constantes.
 */
object Geohash {

    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun encode(latitude: Double, longitude: Double, precision: Int = 4): String {
        require(precision in 1..12) { "precision debe estar entre 1 y 12, era $precision" }
        require(latitude in -90.0..90.0) { "latitud fuera de rango: $latitude" }
        require(longitude in -180.0..180.0) { "longitud fuera de rango: $longitude" }

        var latMin = -90.0
        var latMax = 90.0
        var lonMin = -180.0
        var lonMax = 180.0

        val hash = StringBuilder(precision)
        var bit = 0
        var index = 0
        var evenBit = true

        while (hash.length < precision) {
            if (evenBit) {
                val mid = (lonMin + lonMax) / 2
                if (longitude >= mid) {
                    index = index * 2 + 1
                    lonMin = mid
                } else {
                    index *= 2
                    lonMax = mid
                }
            } else {
                val mid = (latMin + latMax) / 2
                if (latitude >= mid) {
                    index = index * 2 + 1
                    latMin = mid
                } else {
                    index *= 2
                    latMax = mid
                }
            }
            evenBit = !evenBit

            if (++bit == 5) {
                hash.append(BASE32[index])
                bit = 0
                index = 0
            }
        }
        return hash.toString()
    }
}
