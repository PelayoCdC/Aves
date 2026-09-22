package dev.pelayo.aves.core.data

import java.util.Locale

/**
 * Lugares que no son una provincia ni un pais pero que cualquier pajarero
 * conoce por su nombre: Doñana, el delta del Ebro, Monfragüe.
 *
 * GBIF no tiene una capa de espacios naturales que se pueda pedir por nombre,
 * asi que cada lugar se define por un rectangulo de coordenadas que lo envuelve,
 * en WKT, que es lo que acepta el filtro `geometry` de las busquedas. Son
 * rectangulos GENEROSOS a proposito: la lista de aves de un humedal incluye las
 * que cruzan por encima y las de los campos de alrededor, y dejar un margen de
 * unos kilometros se parece mas a lo que ve quien va alli que el limite legal
 * del parque. Y sirven para dos cosas: filtrar la Pokedex por ese lugar y las
 * medallas de logros.
 *
 * Solo España, por ahora: es donde esta quien usa la app. Anadir un lugar es
 * anadir una linea.
 */
object NotablePlaces {

    /** El prefijo de los identificadores, para distinguirlos de los GADM en la base de datos. */
    const val ID_PREFIX = "PLACE:"

    val all: List<PokedexZone> = listOf(
        place("donana", "Doñana", west = -6.65, south = 36.75, east = -6.05, north = 37.25),
        place("delta-ebro", "Delta del Ebro", west = 0.55, south = 40.55, east = 0.95, north = 40.85),
        place("tablas-daimiel", "Tablas de Daimiel", west = -3.80, south = 39.08, east = -3.60, north = 39.20),
        place("monfrague", "Monfragüe", west = -6.15, south = 39.75, east = -5.75, north = 39.95),
        place("picos-europa", "Picos de Europa", west = -5.15, south = 43.05, east = -4.55, north = 43.35),
        place("ordesa", "Ordesa y Monte Perdido", west = -0.20, south = 42.55, east = 0.15, north = 42.75),
        place("guadarrama", "Sierra de Guadarrama", west = -4.25, south = 40.65, east = -3.65, north = 41.05),
        place("cabaneros", "Cabañeros", west = -4.65, south = 39.30, east = -4.25, north = 39.55),
        place("aiguestortes", "Aigüestortes", west = 0.80, south = 42.50, east = 1.15, north = 42.70),
        place("albufera", "Albufera de València", west = -0.40, south = 39.25, east = -0.25, north = 39.40),
        place("gallocanta", "Laguna de Gallocanta", west = -1.60, south = 40.92, east = -1.45, north = 41.05),
        place("cabo-gata", "Cabo de Gata-Níjar", west = -2.25, south = 36.70, east = -1.85, north = 36.95),
        place("urdaibai", "Urdaibai", west = -2.75, south = 43.32, east = -2.60, north = 43.45),
        place("somiedo", "Somiedo", west = -6.40, south = 43.00, east = -6.10, north = 43.20),
        place("villaviciosa", "Ría de Villaviciosa", west = -5.45, south = 43.45, east = -5.35, north = 43.55),
        place("santona", "Marismas de Santoña", west = -3.50, south = 43.40, east = -3.40, north = 43.48),
        place("villafafila", "Lagunas de Villafáfila", west = -5.70, south = 41.78, east = -5.50, north = 41.90),
        place("emporda", "Aiguamolls de l'Empordà", west = 3.05, south = 42.20, east = 3.15, north = 42.30),
        place("bardenas", "Bardenas Reales", west = -1.60, south = 42.05, east = -1.35, north = 42.25),
        place("andujar", "Sierra de Andújar", west = -4.10, south = 38.10, east = -3.75, north = 38.35),
        place("fuente-piedra", "Laguna de Fuente de Piedra", west = -4.82, south = 37.05, east = -4.70, north = 37.13),
        place("odiel", "Marismas del Odiel", west = -7.00, south = 37.15, east = -6.90, north = 37.30),
    )

    fun byId(id: String): PokedexZone? = all.firstOrNull { it.id == id }

    fun isPlaceId(id: String?): Boolean = id?.startsWith(ID_PREFIX) == true

    private fun place(slug: String, name: String, west: Double, south: Double, east: Double, north: Double) =
        PokedexZone(
            level = ZoneLevel.PLACE,
            id = ID_PREFIX + slug,
            name = name,
            geometry = boxWkt(west, south, east, north),
        )

    /**
     * Un rectangulo como poligono WKT, con los vertices en sentido ANTIHORARIO:
     * GBIF rechaza (o peor, interpreta al reves) los que van en horario. Con la
     * longitud a la derecha y la latitud hacia arriba, antihorario es
     * suroeste, sureste, noreste, noroeste.
     */
    internal fun boxWkt(west: Double, south: Double, east: Double, north: Double): String {
        require(west < east && south < north) { "Rectangulo del reves" }
        fun c(lon: Double, lat: Double) = String.format(Locale.ROOT, "%.4f %.4f", lon, lat)
        return "POLYGON((${c(west, south)},${c(east, south)},${c(east, north)},${c(west, north)},${c(west, south)}))"
    }
}
