package dev.pelayo.aves.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las URLs de las teselas y el encuadre que sale de unos puntos. */
class GbifTilesTest {

    private fun point(id: Long, lat: Double, lon: Double) = MapPoint(id, lat, lon, "p$id", MapPointKind.HEARD)

    @Test
    fun `la tesela del mapa base lleva zoom, x, y, escala y estilo`() {
        assertEquals(
            "https://tile.gbif.org/3857/omt/5/15/11@2x.png?style=gbif-natural",
            GbifTiles.basemapUrl(5, 15, 11),
        )
    }

    @Test
    fun `la tesela de densidad lleva el taxon y los hexagonos`() {
        val url = GbifTiles.densityUrl(2490719, 5, 15, 11)
        assertTrue(url.startsWith("https://api.gbif.org/v2/map/occurrence/density/5/15/11@2x.png?"))
        assertTrue(url.contains("taxonKey=2490719"))
        assertTrue(url.contains("bin=hex"))
        assertTrue(url.contains("style=classic-noborder.poly"))
    }

    @Test
    fun `sin puntos, el encuadre de respaldo`() {
        assertEquals(MapViewport.SPAIN, MapViewport.fitting(emptyList()))
    }

    @Test
    fun `un punto solo se mira de cerca, centrado en el`() {
        val viewport = MapViewport.fitting(listOf(point(1, 43.36, -5.85)))
        assertEquals(43.36, viewport.latitude, 1e-9)
        assertEquals(-5.85, viewport.longitude, 1e-9)
        assertEquals(MapViewport.SINGLE_POINT_ZOOM, viewport.zoom, 0.0)
    }

    @Test
    fun `varios puntos se centran en su caja y caben todos`() {
        // Oviedo y Sevilla: casi toda España de norte a sur.
        val viewport = MapViewport.fitting(listOf(point(1, 43.36, -5.85), point(2, 37.39, -5.99)))
        assertEquals((43.36 + 37.39) / 2, viewport.latitude, 1e-9)
        assertTrue("zoom ${viewport.zoom}", viewport.zoom in 4.5..6.5)
    }

    @Test
    fun `cuanto mas juntos los puntos, mas zoom, hasta el tope`() {
        val far = MapViewport.fitting(listOf(point(1, 43.0, -8.0), point(2, 37.0, 2.0)))
        val near = MapViewport.fitting(listOf(point(1, 43.36, -5.85), point(2, 43.40, -5.80)))
        val same = MapViewport.fitting(listOf(point(1, 43.36, -5.85), point(2, 43.361, -5.851)))
        assertTrue(far.zoom < near.zoom)
        assertTrue(near.zoom <= MapViewport.SINGLE_POINT_ZOOM)
        assertEquals(MapViewport.SINGLE_POINT_ZOOM, same.zoom, 0.0)
    }

    @Test
    fun `el zoom nunca baja del minimo de las teselas`() {
        val world = MapViewport.fitting(listOf(point(1, 60.0, -150.0), point(2, -40.0, 170.0)))
        assertTrue(world.zoom >= GbifTiles.MIN_ZOOM)
    }
}
