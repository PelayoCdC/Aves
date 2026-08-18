package dev.pelayo.aves.core.audio

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Solo se prueba la conversion de muestras: la codificacion AAC necesita
 * MediaCodec y MediaMuxer, que en la JVM son stubs que lanzan al tocarlos (ver
 * el KDoc de [Pcm16Converter]).
 */
class Pcm16ConverterTest {

    /** Se compara en Int para que el mensaje de fallo sea legible y no ambiguo. */
    private fun convert(sample: Float, scaling: PcmScaling): Int =
        Pcm16Converter.toPcm16(sample, scaling).toInt()

    @Test
    fun `en escala cruda la muestra pasa tal cual`() {
        assertEquals(0, convert(0f, PcmScaling.RAW))
        assertEquals(1234, convert(1234f, PcmScaling.RAW))
        assertEquals(-1234, convert(-1234f, PcmScaling.RAW))
    }

    @Test
    fun `en escala normalizada el fondo de escala llega a los extremos`() {
        assertEquals(32767, convert(1f, PcmScaling.NORMALIZED))
        assertEquals(-32768, convert(-1f, PcmScaling.NORMALIZED))
        assertEquals(0, convert(0f, PcmScaling.NORMALIZED))
        assertEquals(16384, convert(0.5f, PcmScaling.NORMALIZED))
    }

    @Test
    fun `un valor fuera de rango se recorta en vez de envolver`() {
        // El fallo clasico: 40000f.toInt().toShort() da -25536, un salto de
        // positivo a negativo que se oye como un chasquido seco.
        assertEquals(32767, convert(40_000f, PcmScaling.RAW))
        assertEquals(-32768, convert(-40_000f, PcmScaling.RAW))
        assertEquals(32767, convert(2f, PcmScaling.NORMALIZED))
        assertEquals(-32768, convert(-3f, PcmScaling.NORMALIZED))
    }

    @Test
    fun `el positivo justo por encima del maximo satura y no cambia de signo`() {
        // +32768 no cabe en un Short: sin recorte se convertiria en -32768.
        val converted = convert(32_768f, PcmScaling.RAW)
        assertTrue("no debe cambiar de signo, era $converted", converted > 0)
        assertEquals(32767, converted)
        // El extremo negativo si es representable y debe conservarse intacto.
        assertEquals(-32768, convert(-32_768f, PcmScaling.RAW))
    }

    @Test
    fun `redondea al entero mas proximo en vez de truncar`() {
        assertEquals(101, convert(100.6f, PcmScaling.RAW))
        assertEquals(100, convert(100.4f, PcmScaling.RAW))
        assertEquals(-101, convert(-100.6f, PcmScaling.RAW))
    }

    @Test
    fun `un NaN se trata como silencio y no rompe la conversion`() {
        assertEquals(0, convert(Float.NaN, PcmScaling.RAW))
    }

    @Test
    fun `los infinitos tambien saturan`() {
        assertEquals(32767, convert(Float.POSITIVE_INFINITY, PcmScaling.RAW))
        assertEquals(-32768, convert(Float.NEGATIVE_INFINITY, PcmScaling.RAW))
    }

    @Test
    fun `los dos escalados producen el mismo PCM para la misma senal`() {
        // Es la propiedad que hace que dar el scaling equivocado sea un fallo
        // silencioso: el mismo audio debe salir identico por los dos caminos.
        val raw = FloatArray(64) { (it - 32) * 512f }
        val normalized = FloatArray(64) { raw[it] / 32768f }

        assertArrayEqualsShort(
            Pcm16Converter.toPcm16(raw, PcmScaling.RAW),
            Pcm16Converter.toPcm16(normalized, PcmScaling.NORMALIZED),
        )
    }

    @Test
    fun `los bytes salen en little endian y de dos en dos`() {
        // AAC espera PCM de 16 bits little-endian; invertir el orden daria ruido.
        val bytes = Pcm16Converter.toPcm16Bytes(floatArrayOf(1f, 258f), PcmScaling.RAW)

        assertEquals(4, bytes.size)
        assertEquals(0x01.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertEquals(0x02.toByte(), bytes[2]) // 258 = 0x0102
        assertEquals(0x01.toByte(), bytes[3])
    }

    @Test
    fun `una senal vacia produce cero bytes`() {
        assertEquals(0, Pcm16Converter.toPcm16Bytes(FloatArray(0), PcmScaling.RAW).size)
    }

    private fun assertArrayEqualsShort(expected: ShortArray, actual: ShortArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("posicion $i", expected[i].toInt(), actual[i].toInt())
        }
    }
}

class ClipStorageTest {

    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("aves-clips").toFile()
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    @Test
    fun `el recorte vive en un directorio por sesion`() {
        val clip = ClipStorage.clipFile(baseDir, sessionId = 42, speciesId = "eurbla")

        assertEquals("eurbla.m4a", clip.name)
        assertEquals("session_42", clip.parentFile.name)
        assertEquals(baseDir, clip.parentFile.parentFile)
    }

    @Test
    fun `la ruta es una funcion pura de sesion y especie`() {
        // Es lo que permite a la Pokedex saber si hay audio con un exists(), sin
        // guardar la ruta en la base de datos.
        val a = ClipStorage.clipFile(baseDir, 7, "Turdus merula")
        val b = ClipStorage.clipFile(baseDir, 7, "Turdus merula")

        assertEquals(a, b)
    }

    @Test
    fun `sesiones distintas no comparten fichero`() {
        val a = ClipStorage.clipFile(baseDir, 7, "eurbla")
        val b = ClipStorage.clipFile(baseDir, 8, "eurbla")

        assertNotEquals(a, b)
    }

    @Test
    fun `especies distintas de la misma sesion no comparten fichero`() {
        val a = ClipStorage.clipFile(baseDir, 7, "eurbla")
        val b = ClipStorage.clipFile(baseDir, 7, "eurrob")

        assertEquals(a.parentFile, b.parentFile)
        assertNotEquals(a.name, b.name)
    }

    @Test
    fun `sanea los caracteres que no valen en un nombre de fichero`() {
        // Un nombre cientifico trae espacios, y un id mal formado podria traer
        // barras: sin sanear, la ruta se iria a otro directorio.
        assertEquals(
            "Turdus_merula.m4a",
            ClipStorage.clipFile(baseDir, 1, "Turdus merula").name,
        )
        assertEquals(
            "_etc_passwd.m4a",
            ClipStorage.clipFile(baseDir, 1, "/etc/passwd").name,
        )
        // U+00F1 (la n con virgulilla) es letra, pero no ASCII, y hay sistemas
        // de ficheros que la normalizan de otra forma. Se construye por codigo
        // para no meter caracteres no ASCII en el fuente.
        val nonAscii = "sp" + 241.toChar()
        assertEquals(
            "sp_.m4a",
            ClipStorage.clipFile(baseDir, 1, nonAscii).name,
        )
    }

    @Test
    fun `conserva letras digitos y guiones`() {
        assertEquals(
            "sp-123abc.m4a",
            ClipStorage.clipFile(baseDir, 1, "sp-123abc").name,
        )
    }

    @Test
    fun `rechaza un identificador de especie en blanco`() {
        assertThrows(IllegalArgumentException::class.java) {
            ClipStorage.clipFile(baseDir, 1, "   ")
        }
    }

    @Test
    fun `borrar una sesion no toca las demas`() = runTest {
        writeClip(1, "eurbla", 100)
        writeClip(1, "eurrob", 100)
        writeClip(2, "eurbla", 100)

        val deleted = ClipStorage.deleteClipsFor(baseDir, sessionId = 1)

        assertEquals(2, deleted)
        assertFalse(ClipStorage.clipFile(baseDir, 1, "eurbla").exists())
        assertTrue(ClipStorage.clipFile(baseDir, 2, "eurbla").exists())
    }

    @Test
    fun `borrar una sesion inexistente no falla y devuelve cero`() = runTest {
        assertEquals(0, ClipStorage.deleteClipsFor(baseDir, sessionId = 99))
    }

    @Test
    fun `al borrar la sesion desaparece tambien su directorio`() = runTest {
        writeClip(3, "eurbla", 10)
        ClipStorage.deleteClipsFor(baseDir, sessionId = 3)

        assertFalse(File(baseDir, "session_3").exists())
    }

    @Test
    fun `el total suma los recortes de todas las sesiones`() = runTest {
        writeClip(1, "eurbla", 100)
        writeClip(2, "eurrob", 250)

        assertEquals(350L, ClipStorage.totalBytes(baseDir))
    }

    @Test
    fun `el total ignora ficheros que no son recortes`() = runTest {
        writeClip(1, "eurbla", 100)
        File(baseDir, "session_1").resolve("notas.txt").writeBytes(ByteArray(500))

        assertEquals(100L, ClipStorage.totalBytes(baseDir))
    }

    @Test
    fun `el total de un directorio inexistente es cero`() = runTest {
        assertEquals(0L, ClipStorage.totalBytes(File(baseDir, "no-existe")))
    }

    private fun writeClip(sessionId: Long, speciesId: String, sizeBytes: Int) {
        val file = ClipStorage.clipFile(baseDir, sessionId, speciesId)
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(sizeBytes))
    }
}
