package dev.pelayo.aves.core.ml.vision

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pelayo.aves.core.ml.api.ObservationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Ejecuta el modelo de imagen REAL.
 *
 * Los tests JVM cubren el parseo del labelmap, pero eso no dice nada sobre si el
 * `.tflite` descargado es el que se espera. Los riesgos concretos que esto
 * cierra:
 *
 * - Que el fichero sea de otra variante y devuelva un numero de clases distinto
 *   al del labelmap. Eso desplazaria los nombres de especie y devolveria el ave
 *   equivocada con toda confianza.
 * - Que la entrada sea uint8 y se alimente float32, o al reves. No da error:
 *   simplemente produce predicciones basura con aspecto perfectamente normal.
 */
@RunWith(AndroidJUnit4::class)
class AiyImageClassifierTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun observationContext() = ObservationContext(
        epochMillis = 0L,
        contextInfluence = 0f,
    )

    /** Imagen sintetica determinista del tamano exacto que pide el modelo. */
    private fun testBitmap(seed: Int = 7): Bitmap {
        val random = Random(seed)
        val size = AiyBirdImageClassifier.INPUT_SIZE
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                bitmap.setPixel(
                    x,
                    y,
                    Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)),
                )
            }
        }
        return bitmap
    }

    @Test
    fun elModeloCargaYSusClasesCuadranConElLabelmap() = runBlocking {
        // El `check` interno del clasificador compara el numero de salidas del
        // tensor con el del labelmap. Si el .tflite no fuera el correcto, esto
        // reventaria aqui en vez de devolver especies desplazadas en silencio.
        AiyBirdImageClassifier(context).use { classifier ->
            classifier.warmUp()
            assertEquals(964, classifier.descriptor.speciesCount)
            assertEquals(224, classifier.inputSize)
            assertEquals("Apache-2.0", classifier.descriptor.licenseSpdx)
            assertTrue(
                "el modelo de imagen SI es distribuible, a diferencia de BirdNET",
                classifier.descriptor.allowsCommercialUse,
            )
        }
    }

    @Test
    fun clasificaUnaImagenYDevuelveCandidatosOrdenados() = runBlocking {
        AiyBirdImageClassifier(context, minScore = 0f).use { classifier ->
            val results = classifier.classify(testBitmap(), observationContext())

            assertTrue("debe devolver algun candidato", results.isNotEmpty())
            assertTrue("como maximo el topK por defecto", results.size <= 3)

            val scores = results.map { it.adjustedScore }
            assertEquals("deben venir de mayor a menor", scores.sortedDescending(), scores)

            // Un clasificador con softmax reparte probabilidad: ningun candidato
            // deberia salirse de [0,1].
            assertTrue(results.all { it.rawScore in 0f..1f })
        }
    }

    @Test
    fun ningunCandidatoEsLaClaseDeFondo() = runBlocking {
        // `background` significa "esto no es un ave". Con una imagen de ruido es
        // probable que gane, y precisamente por eso no debe llegar nunca a la
        // coleccion.
        AiyBirdImageClassifier(context, minScore = 0f, topK = 20).use { classifier ->
            val results = classifier.classify(testBitmap(seed = 99), observationContext())
            assertTrue(results.none { it.rawLabel.equals("background", ignoreCase = true) })
        }
    }

    @Test
    fun todosLosCandidatosTraenSuEspecieCanonica() = runBlocking {
        AiyBirdImageClassifier(context, minScore = 0f).use { classifier ->
            val results = classifier.classify(testBitmap(), observationContext())

            // Fuera de :core:ml nadie debe ver un indice de tensor.
            assertTrue(results.all { !it.speciesId.isNullOrBlank() })
            assertTrue(results.all { it.speciesId!!.contains('_') })
        }
    }

    @Test
    fun rechazaUnBitmapDelTamanoEquivocado() = runBlocking {
        // El recorte y el escalado son responsabilidad de quien captura la
        // imagen. Aceptar cualquier tamano aqui escondería un encuadre mal hecho.
        AiyBirdImageClassifier(context).use { classifier ->
            val wrongSize = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            val error = runCatching {
                classifier.classify(wrongSize, observationContext())
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
        }
    }
}
