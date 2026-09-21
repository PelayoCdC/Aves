package dev.pelayo.aves.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La resolucion de "que zona toca", que es donde el filtro se puede quedar
 * apuntando a nada.
 */
class PokedexZoneTest {

    private val asturias = HomePlace(
        countryCode = "ES",
        countryName = "España",
        regionId = "ESP.3_1",
        regionName = "Principado de Asturias",
        provinceId = "ESP.3.1_1",
        provinceName = "Asturias",
    )

    private fun preferences(level: ZoneLevel, home: HomePlace) =
        PokedexPreferences(zoneLevel = level, showMissing = true, home = home, isInitialized = true)

    @Test
    fun `cada nivel da su zona con el identificador que entiende GBIF`() {
        assertEquals(
            PokedexZone(ZoneLevel.PROVINCE, "ESP.3.1_1", "Asturias"),
            preferences(ZoneLevel.PROVINCE, asturias).zone,
        )
        assertEquals(
            PokedexZone(ZoneLevel.REGION, "ESP.3_1", "Principado de Asturias"),
            preferences(ZoneLevel.REGION, asturias).zone,
        )
        assertEquals(
            PokedexZone(ZoneLevel.COUNTRY, "ES", "España"),
            preferences(ZoneLevel.COUNTRY, asturias).zone,
        )
        assertEquals(PokedexZone.World, preferences(ZoneLevel.WORLD, asturias).zone)
    }

    @Test
    fun `si no se conoce la provincia se cae al siguiente nivel que si`() {
        // Sin ubicacion solo se sabe el pais del movil: pedir "provincia" tiene
        // que ensenar el pais, no un error ni una lista vacia.
        val onlyCountry = HomePlace(countryCode = "ES", countryName = "España")

        assertEquals(ZoneLevel.COUNTRY, preferences(ZoneLevel.PROVINCE, onlyCountry).zone.level)
        assertEquals(ZoneLevel.COUNTRY, preferences(ZoneLevel.REGION, onlyCountry).zone.level)
    }

    @Test
    fun `sin nada conocido solo queda el mundo`() {
        assertEquals(PokedexZone.World, preferences(ZoneLevel.PROVINCE, HomePlace()).zone)
        assertFalse(HomePlace().isKnown)
    }

    @Test
    fun `nunca se sube a un nivel mas fino del pedido`() {
        // Quien eligio "pais" no quiere volver a su provincia porque se conozca.
        assertEquals(ZoneLevel.COUNTRY, preferences(ZoneLevel.COUNTRY, asturias).zone.level)
    }

    @Test
    fun `el filtro por defecto se afina solo cuando llega la provincia`() {
        // La primera vez se pide "provincia" aunque solo se sepa el pais (la
        // Pokedex se puede abrir antes de conceder la ubicacion)...
        val onlyCountry = HomePlace(countryCode = "ES", countryName = "España")
        assertEquals(ZoneLevel.COUNTRY, preferences(ZoneLevel.PROVINCE, onlyCountry).zone.level)
        // ...y el dia que se conoce, el mismo ajuste guardado ya la ensena.
        assertEquals(ZoneLevel.PROVINCE, preferences(ZoneLevel.PROVINCE, asturias).zone.level)
        assertTrue(asturias.isKnown)
    }

    // --- Que queda tras un intento de deteccion -----------------------------

    @Test
    fun `lo que dice el geocodificador manda`() {
        val leon = HomePlace(countryCode = "ES", regionId = "ESP.7_1", provinceId = "ESP.7.5_1")
        assertEquals(leon, HomePlace.merge(previous = asturias, located = leon, fallbackCountryCode = "ES"))
    }

    @Test
    fun `un intento sin red no borra la provincia que ya se sabia`() {
        assertEquals(asturias, HomePlace.merge(previous = asturias, located = null, fallbackCountryCode = "ES"))
        assertEquals(asturias, HomePlace.merge(previous = asturias, located = null, fallbackCountryCode = null))
    }

    @Test
    fun `mar adentro el geocodificador contesta vacio y tampoco borra nada`() {
        assertEquals(asturias, HomePlace.merge(previous = asturias, located = HomePlace(), fallbackCountryCode = "ES"))
    }

    @Test
    fun `si el movil ha cambiado de pais la provincia vieja ya no vale`() {
        assertEquals(
            HomePlace(countryCode = "FR"),
            HomePlace.merge(previous = asturias, located = null, fallbackCountryCode = "FR"),
        )
    }

    @Test
    fun `la primera vez sin ubicacion queda el pais del movil`() {
        assertEquals(
            HomePlace(countryCode = "ES"),
            HomePlace.merge(previous = HomePlace(), located = null, fallbackCountryCode = "ES"),
        )
        assertFalse(HomePlace.merge(HomePlace(), located = null, fallbackCountryCode = null).isKnown)
    }

    @Test
    fun `un nivel guardado que ya no existe no rompe nada`() {
        assertEquals(ZoneLevel.WORLD, ZoneLevel.fromName("COMARCA"))
        assertEquals(ZoneLevel.WORLD, ZoneLevel.fromName(null))
        assertEquals(ZoneLevel.REGION, ZoneLevel.fromName("REGION"))
    }
}
