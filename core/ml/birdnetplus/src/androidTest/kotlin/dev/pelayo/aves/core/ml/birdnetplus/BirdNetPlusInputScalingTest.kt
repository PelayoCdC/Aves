package dev.pelayo.aves.core.ml.birdnetplus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pelayo.aves.core.ml.api.ObservationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * El gemelo de `InputScalingRegressionTest` para BirdNET+.
 *
 * Para BirdNET v2.4 la invariancia a la escala de entrada esta demostrada.
 * Para BirdNET+ V3 se daba por hecha citando ese test, que no lo cubre: es
 * otro grafo, exportado desde PyTorch, con un preprocesado distinto (mel, log,
 * media y desviacion tipica fijas, y un min-max por encima). Que sea o no
 * invariante decide si la app puede pasarle audio en cualquier escala o solo
 * en [-1, 1].
 *
 * La app usa [-1, 1] en todas partes justamente para que este test sea
 * informativo y no critico: si pasa, cualquier escala vale; si falla, la app
 * ya esta usando la escala buena y este test documenta por que no se puede
 * cambiar.
 */
@RunWith(AndroidJUnit4::class)
class BirdNetPlusInputScalingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun observationContext() = ObservationContext(epochMillis = 0L, contextInfluence = 0f)

    /** Barrido de frecuencia mas ruido, en [-1, 1], a los 32 kHz de este modelo. */
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
    fun laEscalaNormalizadaYLaCrudaDanElMismoOrden() = runBlocking {
        val signal = syntheticSignal()
        val normalized = signal.copyOf()
        val raw = FloatArray(signal.size) { signal[it] * 32768f }

        BirdNetPlusAudioClassifier(context, topK = 10, minScore = 0f).use { classifier ->
            val fromNormalized = classifier.classify(normalized, observationContext())
            val fromRaw = classifier.classify(raw, observationContext())

            assertEquals(
                "El orden de los candidatos deberia ser identico con ambas escalas. Si esto " +
                    "falla, BirdNET+ NO es invariante y la app debe seguir en [-1, 1].",
                fromNormalized.map { it.labelIndex },
                fromRaw.map { it.labelIndex },
            )

            val normalizedById = fromNormalized.associateBy { it.labelIndex }
            var maxDelta = 0f
            for (result in fromRaw) {
                val match = normalizedById[result.labelIndex] ?: continue
                maxDelta = maxOf(maxDelta, abs(match.rawScore - result.rawScore))
            }
            assertTrue("las puntuaciones difieren hasta $maxDelta entre escalas", maxDelta < 0.05f)
        }
    }

    @Test
    fun unaGananciaModeradaNoCambiaElResultado() = runBlocking {
        // Lo que de verdad pasa en el campo: el mismo canto mas cerca o mas
        // lejos. Esto tiene que aguantarlo cualquier modelo util, sea o no
        // invariante a escalas extremas.
        val signal = syntheticSignal(seed = 7)
        val quiet = FloatArray(signal.size) { signal[it] * 0.25f }
        val loud = FloatArray(signal.size) { signal[it] * 1.6f }

        BirdNetPlusAudioClassifier(context, topK = 5, minScore = 0f).use { classifier ->
            val fromQuiet = classifier.classify(quiet, observationContext())
            val fromLoud = classifier.classify(loud, observationContext())

            assertEquals(
                "El mejor candidato debe ser el mismo a 12 dB de diferencia",
                fromQuiet.first().labelIndex,
                fromLoud.first().labelIndex,
            )
        }
    }
}
