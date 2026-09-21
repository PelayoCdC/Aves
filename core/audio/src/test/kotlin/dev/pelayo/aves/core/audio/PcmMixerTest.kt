package dev.pelayo.aves.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La parte del decodificador de ficheros que no necesita Android. Extraer y
 * decodificar solo se puede probar en dispositivo; mezclar a mono, recortar y
 * no pasarse de memoria, aqui.
 */
class PcmMixerTest {

    private fun ShortAccumulator.toList(): List<Short> = backingArray().take(size)

    @Test
    fun `mono pasa tal cual`() {
        val out = ShortAccumulator(maxSize = 10)
        val written = PcmMixer.mixToMono(shortArrayOf(1, 2, 3), frames = 3, channels = 1, into = out)

        assertEquals(3, written)
        assertEquals(listOf<Short>(1, 2, 3), out.toList())
    }

    @Test
    fun `estereo se promedia y no se queda con un canal`() {
        // Un ave a la derecha de una grabadora estereo estaria a medio volumen
        // (o ausente) si se cogiera solo el canal izquierdo.
        val out = ShortAccumulator(maxSize = 10)
        PcmMixer.mixToMono(shortArrayOf(0, 1000, 200, 400), frames = 2, channels = 2, into = out)

        assertEquals(listOf<Short>(500, 300), out.toList())
    }

    @Test
    fun `promediar no desborda con dos canales a fondo de escala`() {
        val out = ShortAccumulator(maxSize = 10)
        PcmMixer.mixToMono(shortArrayOf(32767, 32767, -32768, -32768), frames = 2, channels = 2, into = out)

        assertEquals(listOf<Short>(32767, -32768), out.toList())
    }

    @Test
    fun `al llegar al tope deja de escribir y lo dice`() {
        val out = ShortAccumulator(maxSize = 2)
        val written = PcmMixer.mixToMono(shortArrayOf(1, 2, 3, 4), frames = 4, channels = 1, into = out)

        assertEquals(2, written)
        assertTrue(out.isFull)
        assertFalse(out.add(9))
        assertEquals(listOf<Short>(1, 2), out.toList())
    }

    @Test
    fun `el acumulador crece sin perder lo que llevaba`() {
        val out = ShortAccumulator(maxSize = 1000, initialCapacity = 2)
        repeat(100) { out.add(it.toShort()) }

        assertEquals(100, out.size)
        assertArrayEquals(ShortArray(100) { it.toShort() }, out.backingArray().copyOf(100))
    }

    @Test
    fun `el PCM flotante se recorta en vez de dar la vuelta`() {
        assertEquals(0.toShort(), PcmMixer.floatToPcm16(0f))
        assertEquals(32767.toShort(), PcmMixer.floatToPcm16(1f))
        assertEquals(32767.toShort(), PcmMixer.floatToPcm16(1.7f))
        assertEquals((-32768).toShort(), PcmMixer.floatToPcm16(-1.7f))
        assertEquals(0.toShort(), PcmMixer.floatToPcm16(Float.NaN))
    }

    @Test
    fun `reconoce una cabecera RIFF WAVE y nada mas`() {
        val wav = "RIFF....WAVEfmt ".toByteArray(Charsets.US_ASCII)
        val mp3 = "ID3.........".toByteArray(Charsets.US_ASCII)
        val avi = "RIFF....AVI LIST".toByteArray(Charsets.US_ASCII)

        assertTrue(AudioFileDecoder.isRiffWave(wav))
        assertFalse(AudioFileDecoder.isRiffWave(mp3))
        assertFalse("RIFF tambien es el contenedor de AVI", AudioFileDecoder.isRiffWave(avi))
        assertFalse(AudioFileDecoder.isRiffWave(ByteArray(4)))
    }

    @Test
    fun `remuestrear solo la parte valida de un buffer mas grande`() {
        // El acumulador entrega su array interno, que casi siempre es mas grande
        // que su contenido. Lo que sobra son ceros y no deben llegar a la salida.
        val buffer = ShortArray(48_000)
        for (i in 0 until 24_000) buffer[i] = 1000

        val out = Resampler.resample(buffer, from = 48_000, to = 32_000, length = 24_000)

        assertEquals(16_000, out.size)
        assertEquals(1000, out[8_000].toInt())
    }
}
