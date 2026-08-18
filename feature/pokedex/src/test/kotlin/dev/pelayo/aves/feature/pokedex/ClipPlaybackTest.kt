package dev.pelayo.aves.feature.pokedex

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Lo que se prueba aqui es todo lo que se puede probar sin dispositivo: si hay
 * algo que reproducir y cual de las tarjetas suena. La reproduccion en si
 * (ExoPlayer) no se puede tocar en la JVM, igual que pasa con el codificador en
 * `core:audio`.
 */
class ClipPlaybackTest {

    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("aves-clips-test").toFile()
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    private fun clip(name: String, bytes: Int = 24_000): File =
        File(baseDir, name).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes))
        }

    // --- Localizar el recorte ----------------------------------------------

    @Test
    fun `una observacion sin recorte no tiene audio`() {
        assertNull(resolveClipFile(null, baseDir))
        assertFalse(hasPlayableClip(null, baseDir))
    }

    @Test
    fun `una ruta en blanco no tiene audio`() {
        // Una cadena vacia en la columna es un dato sucio, no una ruta valida al
        // directorio de trabajo.
        assertNull(resolveClipFile("", baseDir))
        assertNull(resolveClipFile("   ", baseDir))
    }

    @Test
    fun `un recorte que existe se puede reproducir`() {
        val file = clip("session_1/turmer.m4a")

        assertEquals(file, resolveClipFile(file.absolutePath, baseDir))
        assertTrue(hasPlayableClip(file.absolutePath, baseDir))
    }

    @Test
    fun `un recorte borrado degrada a sin audio en vez de reventar`() {
        // El caso real: el usuario borra los datos de la app y la fila de la base
        // de datos sigue apuntando a un fichero que ya no esta.
        val file = clip("session_1/turmer.m4a")
        val path = file.absolutePath
        assertTrue(file.delete())

        assertNull(resolveClipFile(path, baseDir))
        assertFalse(hasPlayableClip(path, baseDir))
    }

    @Test
    fun `un recorte de cero bytes no cuenta como audio`() {
        // Una escritura que se quedo a medias: darselo al reproductor solo
        // produce un error asincrono en vez de un boton desactivado.
        val file = clip("session_1/vacio.m4a", bytes = 0)

        assertNull(resolveClipFile(file.absolutePath, baseDir))
    }

    @Test
    fun `un directorio no es un recorte`() {
        val dir = File(baseDir, "session_1").apply { mkdirs() }

        assertNull(resolveClipFile(dir.absolutePath, baseDir))
    }

    @Test
    fun `una ruta relativa se resuelve contra el directorio base`() {
        clip("session_7/eurrob.m4a")

        val resolved = resolveClipFile("session_7/eurrob.m4a", baseDir)

        assertEquals(File(baseDir, "session_7/eurrob.m4a"), resolved)
    }

    @Test
    fun `una ruta relativa sin directorio base no se puede resolver`() {
        clip("session_7/eurrob.m4a")

        assertNull(resolveClipFile("session_7/eurrob.m4a", baseDir = null))
    }

    @Test
    fun `una ruta absoluta no depende del directorio base`() {
        // Es como se guarda hoy: `File.absolutePath` al escribir el recorte.
        val file = clip("session_2/parmaj.m4a")

        assertEquals(file, resolveClipFile(file.absolutePath, baseDir = null))
    }

    // --- Solo uno sonando --------------------------------------------------

    @Test
    fun `si no suena nada, pulsar arranca`() {
        assertEquals(PlaybackAction.START, playbackActionFor(playingKey = null, requestedKey = 1L))
        assertEquals(1L, playbackAfter(playingKey = null, requestedKey = 1L))
    }

    @Test
    fun `pulsar la que ya suena la para`() {
        // El boton es un interruptor: sin esto no habria forma de callar un canto
        // sin abrir otro.
        assertEquals(PlaybackAction.STOP, playbackActionFor(playingKey = 1L, requestedKey = 1L))
        assertNull(playbackAfter(playingKey = 1L, requestedKey = 1L))
    }

    @Test
    fun `pulsar otra tarjeta desaloja a la anterior`() {
        // Dos cantos solapados no dejan juzgar ninguno de los dos.
        assertEquals(PlaybackAction.SWITCH, playbackActionFor(playingKey = 1L, requestedKey = 2L))
        assertEquals(2L, playbackAfter(playingKey = 1L, requestedKey = 2L))
    }

    @Test
    fun `dos toques seguidos en la misma tarjeta dejan silencio`() {
        val afterFirst = playbackAfter(playingKey = null, requestedKey = 5L)

        assertNull(playbackAfter(afterFirst, requestedKey = 5L))
    }

    @Test
    fun `pulsando en cualquier orden nunca suena mas de una`() {
        // La invariante del sistema, comprobada sobre una secuencia y no sobre un
        // solo salto: da igual como se pulse, siempre queda una clave o ninguna.
        val toques = listOf(1L, 2L, 2L, 3L, 1L, 1L, 4L, 4L, 2L)

        var playing: Long? = null
        val historial = toques.map { key ->
            playing = playbackAfter(playing, key)
            playing
        }

        assertEquals(
            listOf(1L, 2L, null, 3L, 1L, null, 4L, null, 2L),
            historial,
        )
    }
}
