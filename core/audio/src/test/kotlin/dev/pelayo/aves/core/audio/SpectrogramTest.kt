package dev.pelayo.aves.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SpectrogramTest {

    @Test
    fun `la FFT de un impulso es plana`() {
        val re = DoubleArray(8).also { it[0] = 1.0 }
        val im = DoubleArray(8)

        SpectrogramComputer.fft(re, im)

        for (i in 0 until 8) {
            assertEquals(1.0, re[i], 1e-12)
            assertEquals(0.0, im[i], 1e-12)
        }
    }

    @Test
    fun `la FFT coloca un tono en su bin`() {
        val n = 64
        val bin = 5
        val re = DoubleArray(n) { sin(2 * PI * bin * it / n) }
        val im = DoubleArray(n)

        SpectrogramComputer.fft(re, im)

        var peak = 0
        var peakValue = 0.0
        for (i in 0 until n / 2) {
            val magnitude = abs(re[i]) + abs(im[i])
            if (magnitude > peakValue) {
                peakValue = magnitude
                peak = i
            }
        }
        assertEquals(bin, peak)
    }

    @Test
    fun `una columna por salto y tantas filas como bandas`() {
        val computer = SpectrogramComputer(sampleRate = 48_000, scaling = PcmScaling.NORMALIZED)
        val samples = FloatArray(48_000)

        val columns = computer.columns(samples)

        // (48.000 - 1.024) / 512 + 1 ventanas completas.
        assertEquals(92, columns.size)
        assertTrue(columns.all { it.size == computer.bands })
    }

    @Test
    fun `el silencio digital es todo suelo`() {
        val computer = SpectrogramComputer(sampleRate = 48_000, scaling = PcmScaling.NORMALIZED)

        val column = computer.columns(FloatArray(2_048)).first()

        assertTrue(column.all { it == 0f })
    }

    @Test
    fun `un tono ilumina una banda y no las demas`() {
        val rate = 48_000
        val computer = SpectrogramComputer(sampleRate = rate, scaling = PcmScaling.NORMALIZED)
        val tone = FloatArray(2_048) { (0.8 * sin(2 * PI * 4_000.0 * it / rate)).toFloat() }

        val column = computer.columns(tone).first()

        val brightest = column.indices.maxByOrNull { column[it] }!!
        // Con 48 bandas logaritmicas entre 300 Hz y 12 kHz, 4 kHz cae en el
        // ultimo tercio; lo que importa es que sea UNA banda clara y el resto
        // apagado, no el indice exacto.
        assertTrue(column[brightest] > 0.7f)
        val quiet = column.indices.count { abs(it - brightest) > 2 && column[it] < 0.35f }
        assertTrue("casi todas las bandas deben estar apagadas ($quiet)", quiet >= column.size - 6)
    }

    @Test
    fun `respeta el escalado de entrada`() {
        val rate = 48_000
        val raw = SpectrogramComputer(sampleRate = rate, scaling = PcmScaling.RAW)
        val normalized = SpectrogramComputer(sampleRate = rate, scaling = PcmScaling.NORMALIZED)
        val toneNormalized = FloatArray(2_048) { (0.5 * sin(2 * PI * 2_000.0 * it / rate)).toFloat() }
        val toneRaw = FloatArray(toneNormalized.size) { toneNormalized[it] * 32768f }

        val a = raw.columns(toneRaw).first()
        val b = normalized.columns(toneNormalized).first()

        for (i in a.indices) assertEquals(a[i], b[i], 1e-3f)
    }
}
