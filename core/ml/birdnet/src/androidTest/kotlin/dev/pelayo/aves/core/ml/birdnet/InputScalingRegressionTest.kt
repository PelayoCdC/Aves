package dev.pelayo.aves.core.ml.birdnet

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
 * RIESGO #1 — ¿normaliza BirdNET el audio dentro del grafo?
 *
 * Contexto: whoBIRD pasa las muestras Short crudas como Float sin dividir por
 * 32768 y funciona. Eso sugeria que el modelo normaliza internamente, pero no
 * estaba confirmado. Si se acierta mal el modelo NO falla: acierta peor, en
 * silencio, sin excepcion ni log. Es el peor tipo de bug.
 *
 * Inspeccionando el flatbuffer del .tflite aparecen, en el frontend
 * `model/MEL_SPEC1`, las operaciones `Min`, `Max`, `Sub`, `Sub_1` y `truediv`:
 * el patron exacto de una normalizacion min-max `(x - min) / (max - min)`.
 * Una normalizacion asi elimina cualquier factor de ganancia constante, tanto
 * si se aplica sobre el espectro de potencia (donde el factor es multiplicativo)
 * como sobre uno comprimido logaritmicamente (donde es aditivo).
 *
 * Este test comprueba esa hipotesis por el unico camino que la zanja: ejecutar
 * el modelo real con la MISMA senal a dos escalas distintas.
 *
 * No hace falta ninguna grabacion de aves. La invariancia de escala es una
 * propiedad del modelo, no del contenido: si se cumple con una senal
 * sintetica, se cumple con un mirlo.
 */
@RunWith(AndroidJUnit4::class)
class InputScalingRegressionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun observationContext() = ObservationContext(
        epochMillis = 0L,
        contextInfluence = 0f,
    )

    /**
     * Senal sintetica determinista: barrido de frecuencia mas ruido, en [-1, 1].
     * El barrido cubre el rango donde cantan las aves y garantiza que el
     * espectrograma tenga estructura, no una unica linea espectral.
     */
    private fun syntheticSignal(seed: Int = 42): FloatArray {
        val random = Random(seed)
        val n = BirdNetAudioClassifier.SAMPLE_COUNT
        val rate = BirdNetAudioClassifier.SAMPLE_RATE.toFloat()
        return FloatArray(n) { i ->
            val t = i / rate
            val sweep = 1_000f + 6_000f * (i.toFloat() / n)
            val tone = sin(2.0 * Math.PI * sweep * t).toFloat()
            (0.7f * tone + 0.3f * (random.nextFloat() * 2f - 1f)) * 0.5f
        }
    }

    @Test
    fun elModeloEsInvarianteALaEscalaDeEntrada() = runBlocking {
        val signal = syntheticSignal()

        // Misma senal, dos escalas: la convencion de audio [-1,1] y la que usa
        // whoBIRD, que pasa el valor del Short tal cual.
        val normalized = signal.copyOf()
        val raw = FloatArray(signal.size) { signal[it] * 32768f }

        BirdNetAudioClassifier(context, topK = 10, minScore = 0f).use { classifier ->
            val fromNormalized = classifier.classify(normalized, observationContext())
            val fromRaw = classifier.classify(raw, observationContext())

            assertEquals(
                "Ambas escalas deben devolver el mismo numero de candidatos",
                fromNormalized.size,
                fromRaw.size,
            )

            val normalizedById = fromNormalized.associateBy { it.labelIndex }
            var maxDelta = 0f
            for (rawResult in fromRaw) {
                val match = normalizedById[rawResult.labelIndex]
                assertTrue(
                    "La clase ${rawResult.rawLabel} sale con una escala y no con la otra: " +
                        "el modelo NO es invariante a la escala",
                    match != null,
                )
                maxDelta = maxOf(maxDelta, abs(match!!.rawScore - rawResult.rawScore))
            }

            // Tolerancia amplia a proposito: solo se quiere descartar una
            // diferencia REAL. Si el modelo no normalizara, escalar por 32768
            // saturaria el espectrograma y las diferencias serian enormes, no
            // de milesimas.
            assertTrue(
                "Las puntuaciones difieren hasta $maxDelta entre escalas. " +
                    "Si esto falla, el modelo NO normaliza internamente y hay que " +
                    "elegir el escalado correcto a mano.",
                maxDelta < 0.01f,
            )
        }
    }

    @Test
    fun unaGananciaArbitrariaTampocoCambiaElResultado() = runBlocking {
        // Segunda comprobacion, con un factor que no es potencia de dos, para
        // descartar que la coincidencia anterior sea casualidad numerica.
        val signal = syntheticSignal(seed = 7)
        val quiet = FloatArray(signal.size) { signal[it] * 0.013f }
        val loud = FloatArray(signal.size) { signal[it] * 977f }

        BirdNetAudioClassifier(context, topK = 5, minScore = 0f).use { classifier ->
            val fromQuiet = classifier.classify(quiet, observationContext())
            val fromLoud = classifier.classify(loud, observationContext())

            assertEquals(
                "El orden de los candidatos debe ser identico",
                fromQuiet.map { it.labelIndex },
                fromLoud.map { it.labelIndex },
            )
        }
    }

    @Test
    fun elModeloCargaYDevuelveElNumeroDeClasesEsperado() = runBlocking {
        BirdNetAudioClassifier(context, topK = 10_000, minScore = 0f).use { classifier ->
            classifier.warmUp()
            assertEquals(BirdNetLabels.BIRD_SPECIES_COUNT, classifier.descriptor.speciesCount)
            assertEquals(48_000, classifier.requiredSampleRate)
            assertEquals(144_000, classifier.requiredSampleCount)

            // Con umbral 0 salen todas las clases de ave: 6.522 menos las 11 de
            // no-evento y los 90 animales que no son aves. Se usa senal real y
            // no silencio: como el frontend normaliza por min-max, una entrada
            // de ceros dividiria por cero.
            val all = classifier.classify(syntheticSignal(), observationContext())
            assertEquals(BirdNetLabels.BIRD_CLASS_COUNT, all.size)
        }
    }
}
