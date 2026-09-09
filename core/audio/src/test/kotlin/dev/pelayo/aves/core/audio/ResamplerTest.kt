package dev.pelayo.aves.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * El remuestreo hacia abajo tiene que FILTRAR, no solo interpolar.
 *
 * El caso que importa es el del flavor `open`: BirdNET+ pide 32 kHz y todo
 * fichero normal viene a 44,1 o 48. Sin paso-bajo, un tono a 20 kHz se plegaria
 * sobre los 12 kHz y apareceria donde canta un reyezuelo.
 */
class ResamplerTest {

    private fun tone(frequencyHz: Double, sampleRate: Int, seconds: Double = 0.5): ShortArray {
        val n = (sampleRate * seconds).toInt()
        return ShortArray(n) { i -> (sin(2 * PI * frequencyHz * i / sampleRate) * 10_000).toInt().toShort() }
    }

    private fun rms(samples: ShortArray, skip: Int = 200): Double {
        var sum = 0.0
        var count = 0
        for (i in skip until samples.size - skip) {
            sum += samples[i].toDouble() * samples[i]
            count++
        }
        return sqrt(sum / count)
    }

    @Test
    fun `la longitud sale proporcional a la frecuencia`() {
        val out = Resampler.resample(tone(1_000.0, 48_000), 48_000, 32_000)
        assertEquals(16_000, out.size)
    }

    @Test
    fun `a la misma frecuencia devuelve la senal tal cual`() {
        val input = tone(1_000.0, 48_000)
        assertTrue(Resampler.resample(input, 48_000, 48_000) === input)
    }

    @Test
    fun `un tono grave sobrevive a la bajada de frecuencia`() {
        val input = tone(2_000.0, 48_000)
        val out = Resampler.resample(input, 48_000, 32_000)

        // Misma amplitud, con un margen para la transicion del filtro.
        assertEquals(rms(input), rms(out), rms(input) * 0.05)
    }

    @Test
    fun `un tono por encima de la nueva Nyquist se atenua en vez de plegarse`() {
        // 20 kHz esta por encima de los 16 kHz de Nyquist a 32 kHz: sin filtro
        // apareceria como un alias a 12 kHz con toda su energia.
        val input = tone(20_000.0, 48_000)
        val out = Resampler.resample(input, 48_000, 32_000)

        assertTrue(
            "el alias tiene que quedar al menos 20 dB por debajo (rms ${rms(out)})",
            rms(out) < rms(input) * 0.1,
        )
    }

    @Test
    fun `al subir de frecuencia no se filtra nada`() {
        // 2 kHz: bien dentro de banda. La interpolacion lineal atenua cerca de
        // Nyquist tambien al subir, y eso no es lo que se prueba aqui.
        val input = tone(2_000.0, 32_000)
        val out = Resampler.resample(input, 32_000, 48_000)

        assertEquals(24_000, out.size)
        assertEquals(rms(input), rms(out), rms(input) * 0.05)
    }

    @Test
    fun `el filtro tiene ganancia unidad en continua`() {
        val kernel = Resampler.lowPassKernel(cutoffHz = 14_400.0, sampleRate = 48_000)
        assertEquals(1.0, kernel.sum(), 1e-9)
        assertEquals(63, kernel.size)
    }
}
