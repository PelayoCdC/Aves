package dev.pelayo.aves.core.map

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * Las teselas de GBIF: el mapa base y la densidad de registros de una especie.
 *
 * Es lo que sustituye a los mapas de eBird, que no se pueden usar (licencia no
 * comercial y clave). GBIF sirve sin clave dos cosas que juntas dan un mapa de
 * rango real: un mapa base (OpenMapTiles sobre OpenStreetMap) y la densidad de
 * registros de cualquier taxon, en hexagonos, para cualquier zoom. Con el
 * taxon de la especie se ve donde se ha registrado en el mundo; con el de la
 * clase Aves, donde se registran aves cerca de ti.
 *
 * Solo URLs y aritmetica, sin osmdroid, para poder probarlo en la JVM.
 */
object GbifTiles {

    /** El estilo del mapa base: relieve y vegetacion suaves, que es lo que casa con un mapa de aves. */
    const val BASEMAP_STYLE = "gbif-natural"

    /** La clase Aves en el backbone de GBIF: "todas las aves". */
    const val TAXON_KEY_AVES = 212

    /** Hexagonos semitransparentes de amarillo a rojo segun la densidad, sin borde. */
    const val DENSITY_STYLE = "classic-noborder.poly"

    /** Cuantos hexagonos por tesela: mas es mas fino, pero a zoom bajo se convierte en grano. */
    const val HEX_PER_TILE = 40

    /**
     * Teselas de 512 px pedidas a doble resolucion: nitidas en cualquier movil
     * sin que osmdroid tenga que escalarlas.
     */
    const val TILE_SIZE_PX = 512
    const val TILE_SCALE = 2

    const val MIN_ZOOM = 2
    const val MAX_ZOOM = 16

    /** La atribucion que exigen OpenMapTiles y OpenStreetMap, y la de los datos. */
    const val ATTRIBUTION = "© OpenMapTiles · © OpenStreetMap · Registros: GBIF"

    fun basemapUrl(zoom: Int, x: Int, y: Int): String =
        "https://tile.gbif.org/3857/omt/$zoom/$x/$y@${TILE_SCALE}x.png?style=$BASEMAP_STYLE"

    fun densityUrl(taxonKey: Int, zoom: Int, x: Int, y: Int): String =
        "https://api.gbif.org/v2/map/occurrence/density/$zoom/$x/$y@${TILE_SCALE}x.png" +
            "?taxonKey=$taxonKey&bin=hex&hexPerTile=$HEX_PER_TILE&style=$DENSITY_STYLE"
}

/** Un avistamiento en el mapa. */
data class MapPoint(
    val id: Long,
    val latitude: Double,
    val longitude: Double,
    /** Lo que se ensena al tocarlo: el nombre de la especie. */
    val title: String,
    val kind: MapPointKind,
)

/** Como se consiguio: los mismos tres origenes que los iconos de la Pokedex, con su color. */
enum class MapPointKind {
    SEEN,
    PHOTO,
    HEARD,
}

/**
 * Donde mira el mapa: centro y zoom.
 *
 * @param zoom el de las teselas (0 = el mundo, 16 = una calle). Con decimales:
 *   osmdroid los acepta y el encuadre que sale de un conjunto de puntos rara
 *   vez cae en un entero.
 */
data class MapViewport(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
) {
    companion object {
        /** España peninsular entera, para cuando no hay nada mejor que ensenar. */
        val SPAIN = MapViewport(latitude = 40.2, longitude = -3.7, zoom = 5.3)

        /** Zoom para un unico punto: se ve el paraje, no la calle. */
        const val SINGLE_POINT_ZOOM = 11.0

        /**
         * El encuadre que contiene todos los [points], con un margen.
         *
         * El zoom sale de cuantos grados abarca el lado mas ancho: a zoom z el
         * mundo son 2^z teselas, y una pantalla de movil ensena alrededor de
         * una tesela y media de ancho. Se resta un poco para que ningun punto
         * caiga en el borde.
         */
        fun fitting(points: List<MapPoint>, fallback: MapViewport = SPAIN): MapViewport {
            if (points.isEmpty()) return fallback
            if (points.size == 1) return MapViewport(points[0].latitude, points[0].longitude, SINGLE_POINT_ZOOM)

            val minLat = points.minOf { it.latitude }
            val maxLat = points.maxOf { it.latitude }
            val minLon = points.minOf { it.longitude }
            val maxLon = points.maxOf { it.longitude }
            // La latitud se estira en la proyeccion de las teselas: un grado de
            // latitud ocupa mas pantalla que uno de longitud lejos del ecuador.
            val span = max(abs(maxLon - minLon), abs(maxLat - minLat) * LATITUDE_STRETCH)
            val zoom = if (span < MIN_SPAN_DEGREES) {
                SINGLE_POINT_ZOOM
            } else {
                (ln(VISIBLE_DEGREES_AT_ZOOM_ZERO / span) / ln(2.0) - PADDING_ZOOM).coerceIn(GbifTiles.MIN_ZOOM.toDouble(), SINGLE_POINT_ZOOM)
            }
            return MapViewport(
                latitude = (minLat + maxLat) / 2,
                longitude = (minLon + maxLon) / 2,
                zoom = zoom,
            )
        }

        private const val VISIBLE_DEGREES_AT_ZOOM_ZERO = 540.0
        private const val PADDING_ZOOM = 0.4
        private const val LATITUDE_STRETCH = 1.4
        private const val MIN_SPAN_DEGREES = 0.02
    }
}
