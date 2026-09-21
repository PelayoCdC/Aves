package dev.pelayo.aves.feature.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * Lo que una foto de la galeria dice de si misma en su EXIF.
 *
 * Los dos errores que importan son silenciosos: no girar la foto le da al
 * modelo un ave tumbada, y no leer su fecha apunta hoy un pajaro que se vio en
 * las vacaciones del ano pasado.
 */
class GalleryImageTest {

    // Las constantes de android.media.ExifInterface son enteros fijos del
    // estandar EXIF; se escriben aqui a mano porque el android.jar de los tests
    // unitarios no las inicializa.
    private val normal = 1
    private val flipHorizontal = 2
    private val rotate180 = 3
    private val flipVertical = 4
    private val transpose = 5
    private val rotate90 = 6
    private val transverse = 7
    private val rotate270 = 8

    @Test
    fun `cada orientacion EXIF se traduce a sus grados`() {
        assertEquals(0, exifRotationDegrees(normal))
        assertEquals(90, exifRotationDegrees(rotate90))
        assertEquals(180, exifRotationDegrees(rotate180))
        assertEquals(270, exifRotationDegrees(rotate270))
    }

    @Test
    fun `las orientaciones con espejo giran lo mismo que su pareja sin espejo`() {
        // El espejo no se deshace (un ave reflejada sigue siendo la misma ave
        // para el modelo), pero el giro si: es lo que la pone de pie.
        assertEquals(0, exifRotationDegrees(flipHorizontal))
        assertEquals(180, exifRotationDegrees(flipVertical))
        assertEquals(90, exifRotationDegrees(transpose))
        assertEquals(270, exifRotationDegrees(transverse))
    }

    @Test
    fun `una orientacion desconocida no gira nada`() {
        assertEquals(0, exifRotationDegrees(0))
        assertEquals(0, exifRotationDegrees(42))
    }

    @Test
    fun `lee la fecha del EXIF en hora local`() {
        val expected = GregorianCalendar(2026, Calendar.APRIL, 15, 7, 32, 10).timeInMillis

        assertEquals(expected, parseExifDate("2026:04:15 07:32:10"))
        assertEquals(expected, parseExifDate("  2026:04:15 07:32:10 "))
    }

    @Test
    fun `sin fecha o con una fecha rota no se inventa ninguna`() {
        assertNull(parseExifDate(null))
        assertNull(parseExifDate(""))
        // Lo que escriben algunas camaras cuando no tienen reloj.
        assertNull(parseExifDate("0000:00:00 00:00:00"))
        assertNull(parseExifDate("ayer por la tarde"))
        assertNull(parseExifDate("2026:13:45 07:32:10"))
        assertNull(parseExifDate("2026:04:15 07:32:10 basura"))
    }
}
