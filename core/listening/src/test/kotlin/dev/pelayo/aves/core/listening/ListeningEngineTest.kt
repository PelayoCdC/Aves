package dev.pelayo.aves.core.listening

import dev.pelayo.aves.core.audio.AudioFrame
import dev.pelayo.aves.core.audio.FrameSource
import dev.pelayo.aves.core.audio.PcmScaling
import dev.pelayo.aves.core.audio.PlaybackSignal
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier
import dev.pelayo.aves.core.ml.api.ClassificationResult
import dev.pelayo.aves.core.ml.api.ModelDescriptor
import dev.pelayo.aves.core.ml.api.ObservationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * El bucle de escucha entero, sin microfono ni modelo: ventanas fabricadas y un
 * clasificador que dice lo que le digamos. Sin base de datos (el repositorio es
 * nulo), asi que se prueba lo que ve la pantalla: los eventos.
 */
class ListeningEngineTest {

    @After
    fun tearDown() {
        // Estado global: que una prueba no deje "sonando" a la siguiente.
        PlaybackSignal.playbackStopped()
    }

    @Test
    fun `el silencio no gasta inferencias`() = runTest {
        val classifier = FakeClassifier { emptyList() }

        val events = engine(classifier, frames = List(5) { silence() }).listen(null).toList()

        assertEquals(0, classifier.calls)
        val windows = events.filterIsInstance<ListeningEvent.Window>()
        assertEquals(5, windows.size)
        assertEquals(5, windows.last().skipped)
        assertTrue(windows.all { it.inferenceMillis == 0L })
    }

    @Test
    fun `mientras suena un recorte propio no se clasifica`() = runTest {
        val classifier = FakeClassifier { listOf(blackbird(0.9f)) }
        PlaybackSignal.playbackStarted()

        engine(classifier, frames = List(3) { song() }).listen(null).toList()

        assertEquals(0, classifier.calls)
    }

    @Test
    fun `una especie se confirma tras tres ventanas y se anuncia una sola vez`() = runTest {
        val classifier = FakeClassifier { listOf(blackbird(0.9f)) }

        val events = engine(classifier, frames = List(8) { song() }).listen(null).toList()

        assertEquals(8, classifier.calls)
        val announced = events.filterIsInstance<ListeningEvent.Detections>()
        // Antes la racha contaba como cambio y se anunciaba en cada ventana,
        // repintando la lista y la notificacion cada 800 ms.
        assertEquals(1, announced.size)
        assertEquals("turdus_merula", announced.single().detections.single().speciesId)
    }

    @Test
    fun `un fallo del modelo termina en Failed y no en un cierre de la app`() = runTest {
        val classifier = FakeClassifier { error("tensor mal formado") }

        val events = engine(classifier, frames = List(3) { song() }).listen(null).toList()

        val failure = events.filterIsInstance<ListeningEvent.Failed>().single()
        assertEquals("tensor mal formado", failure.cause.message)
        // El flujo termina tras el fallo: nada despues.
        assertTrue(events.last() is ListeningEvent.Failed)
    }

    // --- Utilidades -----------------------------------------------------------

    private fun engine(classifier: AudioBirdClassifier, frames: List<FloatArray>) = ListeningEngine(
        classifier = classifier,
        observationRepository = null,
        regionRepository = null,
        clipsDirectory = null,
        scaling = PcmScaling.NORMALIZED,
        frameSource = { _, _, _, _ -> FakeFrames(frames) },
    )

    private fun silence() = FloatArray(WINDOW)

    /** Un tono de 3 kHz a media escala: bien por encima de la puerta de energia. */
    private fun song() = FloatArray(WINDOW) { 0.5f * sin(2 * PI * 3_000 * it / RATE).toFloat() }

    private fun blackbird(score: Float) = ClassificationResult(
        speciesId = "turdus_merula",
        rawLabel = "Turdus merula_Mirlo comun",
        labelIndex = 7,
        rawScore = score,
        adjustedScore = score,
    )

    private class FakeFrames(private val windows: List<FloatArray>) : FrameSource {
        override val hopSampleCount = HOP
        override val activeSourceName = "prueba"
        override val isSilenced = false

        // Marcas de tiempo en el futuro y muy separadas: la senal de
        // reproduccion descarta tambien las ventanas justo despues de parar (lo
        // que hace el tearDown de otra prueba), y aqui no se quiere eso.
        override fun frames(): Flow<AudioFrame> =
            flowOf(*windows.mapIndexed { index, samples -> AudioFrame(samples, FUTURE + 1_000_000L * index) }.toTypedArray())
    }

    private class FakeClassifier(
        private val answer: () -> List<ClassificationResult>,
    ) : AudioBirdClassifier {
        var calls = 0

        override val descriptor = ModelDescriptor(
            modelId = "prueba",
            displayName = "Prueba",
            licenseSpdx = "Apache-2.0",
            allowsCommercialUse = true,
            attributionText = "",
            speciesCount = 1,
        )
        override val requiredSampleRate = RATE
        override val requiredSampleCount = WINDOW

        override suspend fun warmUp() = Unit

        override suspend fun classify(samples: FloatArray, context: ObservationContext): List<ClassificationResult> {
            calls++
            return answer()
        }

        override fun close() = Unit
    }

    private companion object {
        /** Frecuencia y ventana pequenas: la prueba no necesita 3 s de audio real. */
        const val RATE = 16_000
        const val WINDOW = 4_800
        const val HOP = 1_280

        /** Hacia 2096. */
        const val FUTURE = 4_000_000_000_000L
    }
}
