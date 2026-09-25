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

    /**
     * El centro de la celda [hash], como (latitud, longitud).
     *
     * Es lo que se manda a GBIF en vez de la posicion del usuario: la descarga
     * es por celda y la celda ya dice todo lo que hace falta. Mandar la
     * posicion exacta contaba a un tercero donde estaba alguien con una
     * precision que ni la cache ni la rareza necesitan.
     */
    fun center(hash: String): Pair<Double, Double> {
        require(hash.isNotEmpty()) { "geohash vacio" }
        var latMin = -90.0
        var latMax = 90.0
        var lonMin = -180.0
        var lonMax = 180.0
        var evenBit = true

        for (char in hash) {
            val index = BASE32.indexOf(char.lowercaseChar())
            require(index >= 0) { "caracter no valido en un geohash: '$char'" }
            for (shift in 4 downTo 0) {
                val bitSet = (index shr shift) and 1 == 1
                if (evenBit) {
                    val mid = (lonMin + lonMax) / 2
                    if (bitSet) lonMin = mid else lonMax = mid
                } else {
                    val mid = (latMin + latMax) / 2
                    if (bitSet) latMin = mid else latMax = mid
                }
                evenBit = !evenBit
            }
        }
        return (latMin + latMax) / 2 to (lonMin + lonMax) / 2
    }
}
