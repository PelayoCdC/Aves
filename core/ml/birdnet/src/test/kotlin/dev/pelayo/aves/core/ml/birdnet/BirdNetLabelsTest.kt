package dev.pelayo.aves.core.ml.birdnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BirdNetLabelsTest {

    /** Completa hasta el tamano real del modelo para poder probar el parseo. */
    private fun padLabels(vararg real: String): Sequence<String> {
        val filler = (real.size until BirdNetLabels.EXPECTED_COUNT)
            .map { "Genus species${it}_Relleno $it" }
        return (real.toList() + filler).asSequence()
    }

    private fun padCodes(vararg real: String): Sequence<String> {
        val filler = (real.size until BirdNetLabels.EXPECTED_COUNT).map { "code$it" }
        return (real.toList() + filler).asSequence()
    }

    @Test
    fun `separa nombre cientifico y comun`() {
        val labels = BirdNetLabels.parse(padLabels("Erithacus rubecula_Petirrojo Europeo"))
        assertEquals("Erithacus rubecula", labels[0].scientificName)
        assertEquals("Petirrojo Europeo", labels[0].commonName)
        assertTrue(labels[0].isBird)
    }

    @Test
    fun `marca las once clases que no son seres vivos`() {
        val labels = BirdNetLabels.parse(
            padLabels(
                "Dog_Dog",
                "Noise_Noise",
                "Siren_Siren",
                // Estas tres son las que se cuentan de menos con una expresion
                // regular de binomio latino: parecen "Genero especie".
                "Human vocal_Human vocal",
                "Human non-vocal_Human non-vocal",
                "Power tools_Power tools",
                "Turdus merula_Mirlo Comun",
            ),
        )
        assertTrue(labels.take(6).all { it.isNonEvent })
        assertFalse(labels[6].isNonEvent)
        assertTrue(labels[6].isBird)
    }

    @Test
    fun `distingue animales que no son aves por su codigo taxonomico`() {
        // BirdNET incluye ranas, grillos y sapos a proposito. Sus codigos son
        // identificadores internos `t-XXXXXXXX`; los de las aves son eBird.
        val labels = BirdNetLabels.parse(
            labelLines = padLabels(
                "Anaxyrus americanus_American Toad",
                "Erithacus rubecula_Petirrojo Europeo",
            ),
            taxoCodeLines = padCodes("t-11140103", "eurrob1"),
        )

        assertTrue("un sapo no es un ave", labels[0].isNonBird)
        assertFalse(labels[0].isBird)
        assertFalse(labels[1].isNonBird)
        assertTrue(labels[1].isBird)
    }

    @Test
    fun `sin codigos taxonomicos no se pueden distinguir las no-aves`() {
        // Documenta la limitacion: es la razon de que el clasificador cargue
        // taxo_code.txt ademas del fichero de etiquetas.
        val labels = BirdNetLabels.parse(padLabels("Anaxyrus americanus_American Toad"))
        assertFalse(labels[0].isNonBird)
    }

    @Test
    fun `parte solo por el primer guion bajo`() {
        val labels = BirdNetLabels.parse(padLabels("Falco peregrinus_Halcon_Peregrino"))
        assertEquals("Falco peregrinus", labels[0].scientificName)
        assertEquals("Halcon_Peregrino", labels[0].commonName)
    }

    @Test
    fun `el id provisional coincide con el que genera el catalogo`() {
        val labels = BirdNetLabels.parse(padLabels("Erithacus rubecula_Petirrojo Europeo"))
        assertEquals("erithacus_rubecula", labels[0].provisionalSpeciesId)
    }

    @Test
    fun `revienta si el numero de etiquetas no cuadra con el modelo`() {
        // Es el fallo mas peligroso posible: con el fichero desplazado el modelo
        // sigue funcionando y devolviendo la especie EQUIVOCADA con toda
        // confianza. Debe fallar ruidosamente al cargar, no en silencio.
        val error = runCatching {
            BirdNetLabels.parse(sequenceOf("Erithacus rubecula_Petirrojo Europeo"))
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("6522"))
    }

    @Test
    fun `revienta si los codigos taxonomicos no cuadran con las etiquetas`() {
        val error = runCatching {
            BirdNetLabels.parse(padLabels("Erithacus rubecula_Petirrojo"), sequenceOf("eurrob1"))
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("taxo_code"))
    }
}
