package dev.pelayo.aves.core.ml.vision

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Valida el labelmap REAL empaquetado en assets, no uno sintetico.
 *
 * Los tests JVM prueban la logica de parseo con datos inventados, que es lo
 * correcto para cubrir casos limite. Pero eso no detecta que el fichero que
 * viaja en el APK sea otro: una version distinta, una descarga truncada, o el
 * labelmap de otro modelo.
 *
 * Y ese fallo seria de los peores: el modelo cargaria, clasificaria, y
 * devolveria nombres de especie desplazados con toda confianza.
 */
@RunWith(AndroidJUnit4::class)
class AiyLabelmapRealFileTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun realLabels(): List<AiyLabel> =
        context.assets.open(AiyBirdImageClassifier.LABELS_ASSET).bufferedReader().use { reader ->
            AiyBirdLabels.parse(reader.lineSequence())
        }

    @Test
    fun elLabelmapEmpaquetadoTieneLasClasesEsperadas() {
        val labels = realLabels()
        assertEquals(965, labels.size)
        assertEquals("964 especies mas la clase de fondo", 964, labels.count { !it.isBackground })
    }

    @Test
    fun laClaseDeFondoEstaEnSuIndiceYNoEnElPrimero() {
        // En el CSV real, `background` aparece en la PRIMERA linea pero con id
        // 964. Si el parseo fuera por posicion, quedaria en el indice 0 y todas
        // las especies estarian desplazadas una posicion.
        val labels = realLabels()
        assertTrue("el fondo debe estar en el indice 964", labels[964].isBackground)
        assertTrue("el indice 0 debe ser un ave", !labels[0].isBackground)
    }

    @Test
    fun losIndicesConocidosCoincidenConElFicheroReal() {
        // Valores tomados del labelmap publicado por Google. Si cambian, es que
        // el fichero no es el mismo y el mapeo a especies deja de ser fiable.
        val labels = realLabels()
        assertEquals("Haemorhous cassinii", labels[0].scientificName)
        assertEquals("Aramus guarauna", labels[1].scientificName)
        assertEquals("Ardenna gravis", labels[963].scientificName)
    }

    @Test
    fun todasLasClasesDeAveProducenUnIdUsable() {
        val labels = realLabels()
        val malformed = labels.filter { !it.isBackground }
            .filter { it.provisionalSpeciesId.isBlank() || !it.provisionalSpeciesId.contains('_') }

        assertTrue(
            "hay ${malformed.size} clases cuyo nombre no da un binomio usable: " +
                malformed.take(5).map { it.scientificName },
            malformed.isEmpty(),
        )
    }
}
