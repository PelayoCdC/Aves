package dev.pelayo.aves.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavDecoderTest {

    /** Construye un WAV PCM valido en memoria, con chunks extra opcionales. */
    private fun buildWav(
        samples: ShortArray,
        sampleRate: Int = 48_000,
        channels: Int = 1,
        bitsPerSample: Int = 16,
        audioFormat: Int = 1,
        extraChunkBeforeData: Boolean = false,
    ): ByteArray {
        val dataBytes = ByteArray(samples.size * 2)
        ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)

        val extra = if (extraChunkBeforeData) {
            val payload = "aves-test".toByteArray(Charsets.US_ASCII)
            ByteArrayOutputStream().apply {
                write("LIST".toByteArray(Charsets.US_ASCII))
                write(intLE(payload.size))
                write(payload)
                if (payload.size % 2 == 1) write(0)
            }.toByteArray()
        } else {
            ByteArray(0)
        }

        val body = ByteArrayOutputStream().apply {
            write("WAVE".toByteArray(Charsets.US_ASCII))
            write("fmt ".toByteArray(Charsets.US_ASCII))
            write(intLE(16))
            write(shortLE(audioFormat))
            write(shortLE(channels))
            write(intLE(sampleRate))
            write(intLE(sampleRate * channels * bitsPerSample / 8))
            write(shortLE(channels * bitsPerSample / 8))
            write(shortLE(bitsPerSample))
            write(extra)
            write("data".toByteArray(Charsets.US_ASCII))
            write(intLE(dataBytes.size))
            write(dataBytes)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            write(intLE(body.size))
            write(body)
        }.toByteArray()
    }

    private fun intLE(value: Int) =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun shortLE(value: Int) =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

    @Test
    fun `decodifica mono a 48 kHz sin tocar las muestras`() {
        val wav = buildWav(shortArrayOf(0, 100, -100, 32767, -32768))
        val decoded = WavDecoder.decode(wav, targetSampleRate = 48_000, scaling = PcmScaling.RAW)

        assertEquals(5, decoded.samples.size)
        assertEquals(0f, decoded.samples[0], 0f)
        assertEquals(100f, decoded.samples[1], 0f)
        assertEquals(32767f, decoded.samples[3], 0f)
        assertEquals(48_000, decoded.sampleRate)
        assertEquals(1, decoded.originalChannels)
    }

    @Test
    fun `el escalado normalizado deja las muestras en menos uno a uno`() {
        val wav = buildWav(shortArrayOf(32767, -32768, 0))
        val decoded = WavDecoder.decode(wav, 48_000, PcmScaling.NORMALIZED)

        assertTrue(decoded.samples.all { it >= -1f && it <= 1f })
        assertEquals(-1f, decoded.samples[1], 1e-6f)
    }

    @Test
    fun `mezcla estereo a mono promediando canales`() {
        // L=100 R=200 -> 150 ; L=-100 R=-200 -> -150
        val wav = buildWav(shortArrayOf(100, 200, -100, -200), channels = 2)
        val decoded = WavDecoder.decode(wav, 48_000, PcmScaling.RAW)

        assertEquals(2, decoded.samples.size)
        assertEquals(150f, decoded.samples[0], 0f)
        assertEquals(-150f, decoded.samples[1], 0f)
    }

    @Test
    fun `remuestrea a la frecuencia que pide el modelo`() {
        val wav = buildWav(ShortArray(24_000) { 1000 }, sampleRate = 24_000)
        val decoded = WavDecoder.decode(wav, targetSampleRate = 48_000, scaling = PcmScaling.RAW)

        // Al doblar la frecuencia debe haber aproximadamente el doble de muestras.
        assertEquals(48_000, decoded.samples.size)
        assertEquals(24_000, decoded.originalSampleRate)
        assertEquals(48_000, decoded.sampleRate)
    }

    @Test
    fun `salta chunks desconocidos antes de data`() {
        // El error clasico al parsear WAV es asumir que 'data' viene justo
        // detras de 'fmt '. Muchos ficheros reales meten LIST/INFO en medio.
        val wav = buildWav(shortArrayOf(1, 2, 3), extraChunkBeforeData = true)
        val decoded = WavDecoder.decode(wav, 48_000, PcmScaling.RAW)

        assertEquals(3, decoded.samples.size)
        assertEquals(2f, decoded.samples[1], 0f)
    }

    @Test
    fun `rechaza audio comprimido con un mensaje accionable`() {
        val wav = buildWav(shortArrayOf(1, 2), audioFormat = 85) // MP3 dentro de WAV
        val error = assertThrows(WavFormatException::class.java) {
            WavDecoder.decode(wav, 48_000)
        }
        assertTrue("el mensaje debe decir como arreglarlo", error.message!!.contains("ffmpeg"))
    }

    @Test
    fun `rechaza profundidades distintas de 16 bits`() {
        val wav = buildWav(shortArrayOf(1, 2), bitsPerSample = 24)
        val error = assertThrows(WavFormatException::class.java) {
            WavDecoder.decode(wav, 48_000)
        }
        assertTrue(error.message!!.contains("16 bits"))
    }

    @Test
    fun `rechaza ficheros que no son WAV`() {
        val notWav = ByteArray(100) { 0x42 }
        assertThrows(WavFormatException::class.java) { WavDecoder.decode(notWav, 48_000) }
    }
}

class AudioWindowsTest {

    @Test
    fun `trocea con solape del tamano pedido`() {
        val signal = FloatArray(10) { it.toFloat() }
        val windows = signal.audioWindows(windowSize = 4, hopSize = 2).toList()

        assertEquals(listOf(0f, 1f, 2f, 3f), windows[0].toList())
        assertEquals(listOf(2f, 3f, 4f, 5f), windows[1].toList())
    }

    @Test
    fun `la ultima ventana se rellena con ceros en vez de descartarse`() {
        // Si se descartara, un canto en los ultimos segundos del fichero se
        // perderia por completo.
        val signal = FloatArray(5) { 1f }
        val windows = signal.audioWindows(windowSize = 4, hopSize = 4).toList()

        assertEquals(2, windows.size)
        assertEquals(listOf(1f, 0f, 0f, 0f), windows[1].toList())
    }

    @Test
    fun `una senal mas corta que la ventana produce una ventana rellenada`() {
        val windows = floatArrayOf(1f, 2f).audioWindows(windowSize = 4, hopSize = 2).toList()

        assertEquals(1, windows.size)
        assertEquals(listOf(1f, 2f, 0f, 0f), windows[0].toList())
    }

    @Test
    fun `una senal vacia no produce ventanas`() {
        assertEquals(0, FloatArray(0).audioWindows(4, 2).count())
    }
}

class EnergyGateTest {

    @Test
    fun `el silencio absoluto no pasa el gate`() {
        val gate = EnergyGate(PcmScaling.RAW)
        assertTrue(!gate.shouldClassify(FloatArray(1000)))
    }

    @Test
    fun `una senal fuerte pasa el gate`() {
        val gate = EnergyGate(PcmScaling.RAW)
        val loud = FloatArray(1000) { if (it % 2 == 0) 8000f else -8000f }
        assertTrue(gate.shouldClassify(loud))
    }

    @Test
    fun `el umbral se comporta igual con los dos escalados`() {
        // Es lo que hace que el gate siga siendo valido cuando se resuelva el
        // riesgo #1 y quiza haya que cambiar de escalado.
        val raw = EnergyGate(PcmScaling.RAW)
        val normalized = EnergyGate(PcmScaling.NORMALIZED)

        val rawSignal = FloatArray(1000) { 3276.8f }
        val normalizedSignal = FloatArray(1000) { 0.1f }

        assertEquals(
            raw.normalizedRms(rawSignal),
            normalized.normalizedRms(normalizedSignal),
            1e-4f,
        )
    }

    @Test
    fun `el rms no se satura con ventanas grandes en escala cruda`() {
        // 144.000 muestras a 32767 al cuadrado desbordan un acumulador Float.
        // Si se acumulara en Float, esto daria NaN o un valor absurdo.
        val gate = EnergyGate(PcmScaling.RAW)
        val fullScale = FloatArray(144_000) { 32767f }

        val rms = gate.normalizedRms(fullScale)
        assertEquals(1f, rms, 1e-3f)
    }
}
