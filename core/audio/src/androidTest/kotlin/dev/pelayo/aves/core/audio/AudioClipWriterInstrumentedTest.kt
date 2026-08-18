package dev.pelayo.aves.core.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

/**
 * Cierra el circulo que los tests JVM no pueden cerrar.
 *
 * `MediaCodec` y `MediaMuxer` son stubs en la JVM: cualquier llamada lanza
 * `RuntimeException("Stub!")`. Por eso la conversion Float -> PCM16 se extrajo a
 * `Pcm16Converter`, que si es testeable sin dispositivo. Pero eso deja sin
 * verificar lo unico que de verdad puede salir mal: que el fichero resultante
 * sea un .m4a valido y no un binario corrupto.
 *
 * Un recorte corrupto es un fallo especialmente malo porque no se nota al
 * escribirlo: la escucha termina bien, la observacion entra en la coleccion, y
 * el problema solo aparece semanas despues, cuando alguien intenta reproducir
 * el canto que justificaba una identificacion y no suena nada.
 *
 * Por eso este test no se conforma con que el fichero exista: lo vuelve a abrir
 * con `MediaExtractor` y comprueba que el sistema lo reconoce como AAC.
 */
@RunWith(AndroidJUnit4::class)
class AudioClipWriterInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = File(context.cacheDir, "clip-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    /** Tres segundos de tono a 48 kHz: el formato exacto que produce BirdNET. */
    private fun tone(seconds: Float = 3f, frequency: Float = 2_000f): FloatArray {
        val count = (SAMPLE_RATE * seconds).toInt()
        return FloatArray(count) { i ->
            // En escala RAW, que es la que usa el pipeline en produccion.
            (sin(2.0 * Math.PI * frequency * i / SAMPLE_RATE) * 12_000.0).toFloat()
        }
    }

    @Test
    fun produceUnM4aQueElSistemaSabeLeer() = runBlocking {
        val writer = AudioClipWriter(sampleRate = SAMPLE_RATE, scaling = PcmScaling.RAW)
        val target = ClipStorage.clipFile(baseDir, sessionId = 7, speciesId = "turdus_merula")

        val result = writer.write(tone(), target)

        assertTrue("el fichero debe existir", result.file.exists())
        assertTrue("no puede estar vacio", result.sizeBytes > 0)

        // La comprobacion que importa: que no sea un binario corrupto.
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(result.file.absolutePath)
            assertEquals("un recorte tiene una sola pista de audio", 1, extractor.trackCount)

            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME)
            assertEquals("audio/mp4a-latm", mime)
            assertEquals(SAMPLE_RATE, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals("mono", 1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        } finally {
            extractor.release()
        }
    }

    @Test
    fun comprimeAlMenosDiezVecesFrenteAlWavCrudo() = runBlocking {
        val samples = tone()
        val writer = AudioClipWriter(sampleRate = SAMPLE_RATE, scaling = PcmScaling.RAW)
        val target = ClipStorage.clipFile(baseDir, sessionId = 1, speciesId = "erithacus_rubecula")

        val result = writer.write(samples, target)

        // El WAV equivalente son 2 bytes por muestra. Toda la politica de
        // retencion se apoya en que esta relacion sea grande: sin ella, cien
        // detecciones al dia llenarian el movil.
        val rawWavBytes = samples.size * 2L
        val ratio: Double = rawWavBytes.toDouble() / result.sizeBytes.toDouble()
        assertTrue(
            "se esperaba comprimir >10x y se comprimio ${ratio.toInt()}x " +
                "(${result.sizeBytes} bytes frente a $rawWavBytes)",
            ratio > 10.0,
        )
    }

    @Test
    fun conservaLaDuracionDelRecorte() = runBlocking {
        val writer = AudioClipWriter(sampleRate = SAMPLE_RATE, scaling = PcmScaling.RAW)
        val target = ClipStorage.clipFile(baseDir, sessionId = 2, speciesId = "parus_major")

        val result = writer.write(tone(seconds = 3f), target)

        // Tolerancia amplia: AAC trabaja por tramas de 1024 muestras, asi que la
        // duracion se redondea hacia arriba hasta completar la ultima.
        assertEquals(3_000.0, result.durationMillis.toDouble(), 100.0)
    }

    @Test
    fun cadaEspecieDeUnaSesionTieneSuPropioRecorte() = runBlocking {
        val writer = AudioClipWriter(sampleRate = SAMPLE_RATE, scaling = PcmScaling.RAW)
        val samples = tone(seconds = 1f)

        val mirlo = ClipStorage.clipFile(baseDir, 3, "turdus_merula")
        val petirrojo = ClipStorage.clipFile(baseDir, 3, "erithacus_rubecula")
        writer.write(samples, mirlo)
        writer.write(samples, petirrojo)

        assertTrue(mirlo.exists() && petirrojo.exists())
        assertEquals(2, ClipStorage.deleteClipsFor(baseDir, sessionId = 3))
        assertTrue("borrar por sesion se los lleva todos", !mirlo.exists() && !petirrojo.exists())
    }

    @Test
    fun unaSegundaDeteccionDeLaMismaEspecieSobrescribe() = runBlocking {
        // Es lo que acota el crecimiento: una escucha larga con el mismo mirlo
        // cantando sin parar deja UN recorte, no cincuenta.
        val writer = AudioClipWriter(sampleRate = SAMPLE_RATE, scaling = PcmScaling.RAW)
        val target = ClipStorage.clipFile(baseDir, 4, "turdus_merula")

        writer.write(tone(seconds = 3f), target)
        writer.write(tone(seconds = 1f), target)

        assertEquals(1, ClipStorage.deleteClipsFor(baseDir, sessionId = 4))
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
    }
}
