package dev.pelayo.aves.core.ml.birdnetplus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pelayo.aves.core.ml.api.ObservationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ejecuta BirdNET+ V3 de verdad, con ONNX Runtime.
 *
 * Es el modelo que hace publicable la app: sus pesos son Apache 2.0. Todo lo que
 * hay a su alrededor (el flavor `open`, la guardia de licencias en CI, la
 * abstraccion de `:core:ml:api`) existe para poder llegar hasta aqui, asi que
 * conviene comprobar que no solo carga sino que produce numeros con sentido.
 *
 * Ademas es la prueba de fuego de la arquitectura: este modelo trabaja a 32 kHz
 * y no a 48, y usa ONNX en vez de TFLite. Si el diseño valia lo que prometia,
 * nada de eso obliga a tocar la capa de captura ni las pantallas.
 */
@RunWith(AndroidJUnit4::class)
class BirdNetPlusClassifierTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun observationContext() = ObservationContext(
        epochMillis = 0L,
        contextInfluence = 0f,
    )

    /** Barrido de frecuencia mas ruido, a la frecuencia que pide ESTE modelo. */
    private fun syntheticSignal(seed: Int = 42): FloatArray {
        val random = Random(seed)
        val n = BirdNetPlusAudioClassifier.SAMPLE_COUNT
        val rate = BirdNetPlusAudioClassifier.SAMPLE_RATE.toFloat()
        return FloatArray(n) { i ->
            val t = i / rate
            val sweep = 1_000f + 6_000f * (i.toFloat() / n)
            val tone = sin(2.0 * Math.PI * sweep * t).toFloat()
            (0.7f * tone + 0.3f * (random.nextFloat() * 2f - 1f)) * 0.5f
        }
    }

    @Test
    fun declaraSuPropioFormatoDeAudio() = runBlocking {
        // 32 kHz y 3 s, distinto de la v2.4. La capa de captura se configura
        // leyendo estos campos, asi que cambiar de modelo no la toca.
        BirdNetPlusAudioClassifier(context).use { classifier ->
            assertEquals(32_000, classifier.requiredSampleRate)
            assertEquals(96_000, classifier.requiredSampleCount)
        }
    }

    @Test
    fun susPesosSonDistribuibles() = runBlocking {
        // La razon de ser de este modulo. Si esto cambiara, el flavor `open`
        // dejaria de poder publicarse.
        BirdNetPlusAudioClassifier(context).use { classifier ->
            assertEquals("Apache-2.0", classifier.descriptor.licenseSpdx)
            assertTrue(classifier.descriptor.allowsCommercialUse)
        }
    }

    @Test
    fun cargaYSusEtiquetasCuadranConElModelo() = runBlocking {
        // El `check` interno compara el numero de salidas del grafo con el del
        // fichero de etiquetas. Si no cuadraran, las identificaciones saldrian
        // desplazadas y el modelo no daria ningun sintoma.
        BirdNetPlusAudioClassifier(context).use { classifier ->
            classifier.warmUp()
            assertEquals(BirdNetPlusLabels.BIRD_COUNT, classifier.descriptor.speciesCount)
        }
    }

    @Test
    fun clasificaYDevuelveCandidatosOrdenados() = runBlocking {
        BirdNetPlusAudioClassifier(context, minScore = 0f).use { classifier ->
            val results = classifier.classify(syntheticSignal(), observationContext())

            assertTrue("debe devolver algun candidato", results.isNotEmpty())
            assertTrue(results.size <= 3)

            val scores = results.map { it.adjustedScore }
            assertEquals("de mayor a menor", scores.sortedDescending(), scores)
            assertTrue("probabilidades en rango", results.all { it.rawScore in 0f..1f })
        }
    }

    @Test
    fun soloDevuelveAves() = runBlocking {
        // BirdNET+ incluye 340 anfibios, 269 mamiferos y 257 insectos a
        // proposito. Esta app se llama Aves.
        val labels = context.assets.open(BirdNetPlusAudioClassifier.LABELS_ASSET)
            .bufferedReader()
            .use { BirdNetPlusLabels.parse(it.lineSequence()) }
        val nonBirdIndices = labels.filter { !it.isBird }.map { it.index }.toSet()

        BirdNetPlusAudioClassifier(context, minScore = 0f, topK = 50).use { classifier ->
            val results = classifier.classify(syntheticSignal(seed = 11), observationContext())
            assertTrue(results.none { it.labelIndex in nonBirdIndices })
        }
    }

    @Test
    fun todosLosCandidatosTraenSuEspecieCanonica() = runBlocking {
        BirdNetPlusAudioClassifier(context, minScore = 0f).use { classifier ->
            val results = classifier.classify(syntheticSignal(), observationContext())

            // Fuera de :core:ml nadie ve un indice de tensor.
            assertTrue(results.all { !it.speciesId.isNullOrBlank() })
            assertTrue(results.all { it.speciesId!!.contains('_') })
        }
    }

    @Test
    fun rechazaUnaVentanaDelTamanoEquivocado() = runBlocking {
        // Alimentarlo con las 144.000 muestras de la v2.4 es el error mas
        // probable al cambiar de modelo, y debe fallar ruidosamente.
        BirdNetPlusAudioClassifier(context).use { classifier ->
            val error = runCatching {
                classifier.classify(FloatArray(144_000), observationContext())
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
        }
    }
}
