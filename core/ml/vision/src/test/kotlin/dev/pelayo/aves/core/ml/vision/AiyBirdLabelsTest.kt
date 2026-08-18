package dev.pelayo.aves.core.ml.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiyBirdLabelsTest {

    /**
     * Genera un labelmap del tamano correcto. Se emite DESORDENADO a proposito,
     * porque asi es el fichero real: `background` va en la primera linea con id
     * 964.
     */
    private fun labelmap(vararg overrides: Pair<Int, String>): Sequence<String> {
        val names = HashMap<Int, String>()
        names[BACKGROUND_INDEX] = "background"
        for (index in 0 until BACKGROUND_INDEX) names[index] = "Genus especie$index"
        overrides.forEach { (index, name) -> names[index] = name }

        val header = sequenceOf("id,name")
        // Primero el fondo, luego el resto: replica el desorden del fichero real.
        val body = sequenceOf("$BACKGROUND_INDEX,${names[BACKGROUND_INDEX]}") +
            (0 until BACKGROUND_INDEX).asSequence().map { "$it,${names[it]}" }
        return header + body
    }

    @Test
    fun `indexa por la columna id y no por el orden de las lineas`() {
        // Es LA trampa de este fichero. El changelog del modelo dice que la v3
        // corrige un "out-of-order logit to labelmap mapping"; leerlo por
        // posicion reproduciria el mismo fallo y devolveria la especie
        // equivocada sin dar ningun sintoma.
        val labels = AiyBirdLabels.parse(labelmap(0 to "Haemorhous cassinii"))

        assertEquals("Haemorhous cassinii", labels[0].scientificName)
        assertFalse(labels[0].isBackground)
        assertTrue("el fondo debe quedar en su indice, no en el primero", labels[964].isBackground)
    }

    @Test
    fun `marca la clase de fondo`() {
        val labels = AiyBirdLabels.parse(labelmap())
        assertTrue(labels[BACKGROUND_INDEX].isBackground)
        assertEquals(1, labels.count { it.isBackground })
    }

    @Test
    fun `el id provisional coincide con el que genera el catalogo`() {
        val labels = AiyBirdLabels.parse(labelmap(5 to "Cyanocitta cristata"))
        assertEquals("cyanocitta_cristata", labels[5].provisionalSpeciesId)
    }

    @Test
    fun `revienta si faltan clases`() {
        val error = runCatching {
            AiyBirdLabels.parse(sequenceOf("id,name", "0,Haemorhous cassinii"))
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("965"))
    }

    @Test
    fun `revienta si hay un hueco en la numeracion`() {
        // Un hueco desplazaria todo lo que viene detras.
        val withGap = sequenceOf("id,name") +
            (0 until AiyBirdLabels.EXPECTED_COUNT).asSequence()
                .map { if (it == 500) 9_999 else it }
                .map { "$it,Genus especie$it" }

        val error = runCatching { AiyBirdLabels.parse(withGap) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("hueco"))
    }

    @Test
    fun `ignora lineas vacias y la cabecera`() {
        val padded = labelmap().toMutableList()
        padded.add(2, "")
        padded.add("   ")
        assertEquals(AiyBirdLabels.EXPECTED_COUNT, AiyBirdLabels.parse(padded.asSequence()).size)
    }

    @Test
    fun `tolera nombres con comas`() {
        // Se parte por la PRIMERA coma: el nombre puede contener mas.
        val labels = AiyBirdLabels.parse(labelmap(7 to "Genus especie, variedad"))
        assertEquals("Genus especie, variedad", labels[7].scientificName)
    }

    private companion object {
        const val BACKGROUND_INDEX = 964
    }
}
