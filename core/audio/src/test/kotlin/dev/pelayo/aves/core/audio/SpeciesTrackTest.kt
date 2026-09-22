package dev.pelayo.aves.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * La pista de una especie dentro de una grabacion con varias.
 *
 * Aqui no hay altavoz con el que escuchar el resultado, asi que lo que se puede
 * medir se mide: cuanto baja el ruido, cuanto se respeta el canto y cuanto se
 * atenua lo que canta en otra banda.
 */
class SpeciesTrackTest {

    private val rate = 48_000
    private val window = 3 * rate

    // --- Que tramos ---------------------------------------------------------

    @Test
    fun `las ventanas que se solapan son un solo tramo`() {
        val hop = window / 2
        val segments = SpeciesTrackBuilder.mergeWindows(
            windows = listOf(ScoredWindow(0, 0.5f), ScoredWindow(hop, 0.9f), ScoredWindow(2 * hop, 0.4f)),
            windowSize = window,
            totalSamples = 60 * rate,
        )

        assertEquals(listOf(TrackSegment(0, 2 * hop + window, 0.9f)), segments)
    }

    @Test
    fun `un hueco entre ventanas separa dos tramos`() {
        val segments = SpeciesTrackBuilder.mergeWindows(
            windows = listOf(ScoredWindow(20 * rate, 0.6f), ScoredWindow(0, 0.8f)),
            windowSize = window,
            totalSamples = 60 * rate,
        )

        assertEquals(
            listOf(TrackSegment(0, window, 0.8f), TrackSegment(20 * rate, 20 * rate + window, 0.6f)),
            segments,
        )
    }

    @Test
    fun `la ultima ventana no se sale del fichero`() {
        val total = 10 * rate
        val segments = SpeciesTrackBuilder.mergeWindows(listOf(ScoredWindow(8 * rate, 0.7f)), window, total)
        assertEquals(total, segments.single().endSample)
    }

    @Test
    fun `si no cabe todo se quedan los mejores tramos, en el orden en que suenan`() {
        val segments = listOf(
            TrackSegment(0, 3 * rate, 0.4f),
            TrackSegment(10 * rate, 13 * rate, 0.9f),
            TrackSegment(20 * rate, 23 * rate, 0.7f),
        )

        val chosen = SpeciesTrackBuilder.selectSegments(segments, maxSamples = 6 * rate)

        assertEquals(listOf(10 * rate, 20 * rate), chosen.map { it.startSample })
    }

    @Test
    fun `un tramo que no cabe entero se recorta`() {
        val chosen = SpeciesTrackBuilder.selectSegments(
            listOf(TrackSegment(0, 30 * rate, 0.9f)),
            maxSamples = 20 * rate,
        )
        assertEquals(20 * rate, chosen.single().length)
    }

    @Test
    fun `la pista solo lleva el audio de sus tramos`() {
        // Un fichero con un valor distinto en cada segundo: se ve de donde sale cada muestra.
        val samples = FloatArray(30 * rate) { (it / rate).toFloat() / 100f }
        val track = SpeciesTrackBuilder.build(
            samples = samples,
            sampleRate = rate,
            windows = listOf(ScoredWindow(5 * rate, 0.8f), ScoredWindow(20 * rate, 0.6f)),
            windowSize = window,
        )

        // Dos tramos de 3 s y un silencio entre ellos.
        assertTrue(track.size > 2 * window)
        assertEquals(0.06f, track[window / 2], 1e-6f)
        assertEquals(0.21f, track[track.size - window / 2], 1e-6f)
        // El silencio es silencio, y los bordes de cada tramo arrancan fundidos.
        assertEquals(0f, track[window + 100], 0f)
        assertEquals(0f, track[0], 1e-6f)
    }

    @Test
    fun `sin ventanas no hay pista`() {
        assertEquals(0, SpeciesTrackBuilder.build(FloatArray(rate), rate, emptyList(), window).size)
    }

    // --- Limpieza -----------------------------------------------------------

    private fun tone(frequency: Double, amplitude: Double, length: Int, from: Int = 0, to: Int = length) =
        FloatArray(length) { i ->
            if (i in from until to) (amplitude * sin(2 * PI * frequency * i / rate)).toFloat() else 0f
        }

    private fun noise(length: Int, sigma: Double, seed: Long = 7) =
        Random(seed).let { random -> FloatArray(length) { (random.nextGaussian() * sigma).toFloat() } }

    private fun FloatArray.plus(other: FloatArray) = FloatArray(size) { this[it] + other[it] }

    private fun rms(samples: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (to - from))
    }

    private fun db(ratio: Double) = 20 * log10(ratio)

    /** Amplitud de la componente de [frequency] en el tramo, por correlacion. */
    private fun amplitudeAt(samples: FloatArray, frequency: Double, from: Int, to: Int): Double {
        var re = 0.0
        var im = 0.0
        for (i in from until to) {
            val phase = 2 * PI * frequency * i / rate
            re += samples[i] * kotlin.math.cos(phase)
            im += samples[i] * sin(phase)
        }
        return 2 * sqrt(re * re + im * im) / (to - from)
    }

    @Test
    fun `sin ruido que quitar, la pista sale como entro`() {
        val cleaner = SpectralCleaner()
        val track = tone(3_000.0, 0.3, 2 * rate)

        val cleaned = cleaner.clean(track, DoubleArray(513))

        assertEquals(track.size, cleaned.size)
        val error = (rate / 2 until rate + rate / 2).maxOf { abs(cleaned[it] - track[it]) }
        assertTrue("error de reconstruccion $error", error < 1e-3)
    }

    @Test
    fun `el ruido de fondo baja y el canto se respeta`() {
        val cleaner = SpectralCleaner()
        val length = 6 * rate
        // Un "canto" de un segundo en mitad de ruido blanco continuo.
        val recording = tone(3_000.0, 0.3, length, from = 2 * rate, to = 3 * rate).plus(noise(length, 0.01))

        val cleaned = cleaner.clean(recording, cleaner.noiseProfile(recording))

        val noiseDrop = db(rms(cleaned, 4 * rate, 5 * rate) / rms(recording, 4 * rate, 5 * rate))
        val songChange = db(
            amplitudeAt(cleaned, 3_000.0, 2 * rate + 4_800, 3 * rate - 4_800) /
                amplitudeAt(recording, 3_000.0, 2 * rate + 4_800, 3 * rate - 4_800),
        )
        assertTrue("el ruido solo bajo $noiseDrop dB", noiseDrop < -8.0)
        assertTrue("el canto cambio $songChange dB", abs(songChange) < 1.0)
    }

    @Test
    fun `lo que canta en otra banda se atenua`() {
        val cleaner = SpectralCleaner()
        val length = 3 * rate
        // El protagonista a 3 kHz y, a la vez, otro mucho mas flojo a 9 kHz.
        val track = tone(3_000.0, 0.3, length).plus(tone(9_000.0, 0.02, length))

        val cleaned = cleaner.clean(track, DoubleArray(513))

        val main = db(amplitudeAt(cleaned, 3_000.0, rate, 2 * rate) / amplitudeAt(track, 3_000.0, rate, 2 * rate))
        val other = db(amplitudeAt(cleaned, 9_000.0, rate, 2 * rate) / amplitudeAt(track, 9_000.0, rate, 2 * rate))
        assertTrue("el protagonista cambio $main dB", abs(main) < 0.5)
        assertTrue("el otro solo bajo $other dB", other < -10.0)
    }

    @Test
    fun `un ave que no calla no se confunde con el ruido`() {
        val cleaner = SpectralCleaner()
        val length = 4 * rate
        // Canta TODO el fichero: para el percentil, su banda "siempre esta ahi".
        val recording = tone(4_000.0, 0.2, length).plus(noise(length, 0.005))

        val cleaned = cleaner.clean(recording, cleaner.noiseProfile(recording))

        val change = db(amplitudeAt(cleaned, 4_000.0, rate, 3 * rate) / amplitudeAt(recording, 4_000.0, rate, 3 * rate))
        assertTrue("el canto continuo cayo $change dB", abs(change) < 1.5)
    }

    @Test
    fun `el perfil de ruido recorta los picos estrechos y deja el fondo`() {
        val cleaner = SpectralCleaner()
        val profile = DoubleArray(513) { 1.0 }.also { it[100] = 50.0 }

        val capped = cleaner.capToSmoothFloor(profile)

        assertEquals(1.5, capped[100], 1e-9)
        assertEquals(1.0, capped[300], 1e-9)
    }

    @Test
    fun `una pista mas corta que una trama se devuelve tal cual`() {
        val tiny = FloatArray(100) { 0.1f }
        assertArrayEquals(tiny, SpectralCleaner().clean(tiny, DoubleArray(513)), 0f)
    }

    // --- Volumen ------------------------------------------------------------

    @Test
    fun `una pista floja se sube hasta rozar el fondo de escala`() {
        val quiet = tone(2_000.0, 0.1, rate)
        val loud = normalizePeak(quiet)
        assertEquals(0.9f, loud.maxOf { abs(it) }, 0.01f)
    }

    @Test
    fun `una pista que ya suena no se toca, y el silencio tampoco`() {
        val fine = tone(2_000.0, 0.95, rate)
        assertTrue(normalizePeak(fine) === fine)
        val silence = FloatArray(rate)
        assertTrue(normalizePeak(silence) === silence)
    }

    @Test
    fun `la ganancia tiene tope para no convertir el ruido en un rugido`() {
        val hiss = FloatArray(rate) { if (it % 2 == 0) 0.0001f else -0.0001f }
        assertEquals(0.003f, normalizePeak(hiss).maxOf { abs(it) }, 1e-4f)
    }
}
