package dev.pelayo.aves.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Estos tests usan nombres REALES devueltos por la API de GBIF, capturados de
 * una consulta cerca de Oviedo. Inventarse el formato seria el error clasico:
 * la autoria aparece a veces entre parentesis y a veces no.
 */
class GbifSpeciesNamesTest {

    @Test
    fun `quita la autoria sin parentesis`() {
        assertEquals("turdus_merula", GbifSpeciesNames.toSpeciesId("Turdus merula Linnaeus, 1758"))
        assertEquals("hirundo_rustica", GbifSpeciesNames.toSpeciesId("Hirundo rustica Linnaeus, 1758"))
    }

    @Test
    fun `quita la autoria entre parentesis`() {
        // GBIF pone la autoria entre parentesis cuando la especie se movio de
        // genero desde su descripcion original. Aparecen las dos formas en la
        // misma respuesta.
        assertEquals(
            "erithacus_rubecula",
            GbifSpeciesNames.toSpeciesId("Erithacus rubecula (Linnaeus, 1758)"),
        )
        assertEquals(
            "sylvia_atricapilla",
            GbifSpeciesNames.toSpeciesId("Sylvia atricapilla (Linnaeus, 1758)"),
        )
    }

    @Test
    fun `un binomio limpio pasa tal cual`() {
        assertEquals("parus_major", GbifSpeciesNames.toSpeciesId("Parus major"))
    }

    @Test
    fun `una subespecie se pliega sobre su especie`() {
        // Es lo que queremos: las ocurrencias de subespecies deben sumar al
        // recuento de la especie, no perderse.
        assertEquals(
            "turdus_merula",
            GbifSpeciesNames.toSpeciesId("Turdus merula merula Linnaeus, 1758"),
        )
    }

    @Test
    fun `descarta un genero suelto`() {
        // "Turdus Linnaeus, 1758" es el genero, no una especie: si cruzara,
        // inflaria el recuento de alguna especie al azar.
        assertNull(GbifSpeciesNames.toSpeciesId("Turdus"))
        assertNull(GbifSpeciesNames.toSpeciesId("Turdus Linnaeus, 1758"))
    }

    @Test
    fun `descarta identificaciones inciertas`() {
        assertNull(GbifSpeciesNames.toSpeciesId("Turdus sp."))
        assertNull(GbifSpeciesNames.toSpeciesId("Turdus cf. merula"))
        assertNull(GbifSpeciesNames.toSpeciesId("Anas spp."))
    }

    @Test
    fun `descarta hibridos`() {
        // Un hibrido no es ninguna de las dos especies y no debe sumar a
        // ninguna.
        assertNull(GbifSpeciesNames.toSpeciesId("Anas platyrhynchos x Anas acuta"))
        assertNull(GbifSpeciesNames.toSpeciesId("× Aegithalos caudatus"))
    }

    @Test
    fun `descarta entradas vacias o malformadas`() {
        assertNull(GbifSpeciesNames.toSpeciesId(""))
        assertNull(GbifSpeciesNames.toSpeciesId("   "))
        assertNull(GbifSpeciesNames.toSpeciesId("turdus merula"))
    }

    @Test
    fun `tolera espacios de sobra`() {
        assertEquals("turdus_merula", GbifSpeciesNames.toSpeciesId("  Turdus   merula  Linnaeus "))
    }

    @Test
    fun `el slug coincide con el que genera el script del catalogo`() {
        // Si estas dos normalizaciones divergen, el cruce con GBIF deja de
        // funcionar EN SILENCIO: no hay error, simplemente ninguna especie
        // encuentra sus datos de rareza.
        assertEquals("erithacus_rubecula", GbifSpeciesNames.slug("Erithacus rubecula"))
        assertEquals("parus_major", GbifSpeciesNames.slug("Parus major"))
    }
}
