package dev.pelayo.aves.feature.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los dos fallos que cubren estos tests -recortar torcido y no girar- no tumban
 * la app: devuelven peores candidatos sin decir nada. Por eso se prueban aqui y
 * no confiando en verlo en pantalla.
 */
class ImageConversionTest {

    // --- Recorte cuadrado centrado -----------------------------------------

    @Test
    fun `en horizontal recorta por los lados y conserva el alto`() {
        val crop = centerSquareCrop(width = 640, height = 480)

        assertEquals(480, crop.size)
        assertEquals(80, crop.left)
        assertEquals(0, crop.top)
    }

    @Test
    fun `en vertical recorta por arriba y abajo y conserva el ancho`() {
        val crop = centerSquareCrop(width = 480, height = 640)

        assertEquals(480, crop.size)
        assertEquals(0, crop.left)
        assertEquals(80, crop.top)
    }

    @Test
    fun `una imagen ya cuadrada no se toca`() {
        val crop = centerSquareCrop(width = 512, height = 512)

        assertEquals(512, crop.size)
        assertEquals(0, crop.left)
        assertEquals(0, crop.top)
    }

    @Test
    fun `lo que sobra se reparte por igual a los dos lados`() {
        // Es la definicion de "centrado": si no se cumpliera, el ave se iria
        // hacia un borde y podria salirse del recorte.
        val crop = centerSquareCrop(width = 4000, height = 3000)

        assertEquals(500, crop.left)
        assertEquals(4000 - crop.right, crop.left)
    }

    @Test
    fun `con sobrante impar el recorte sigue cabiendo dentro`() {
        // 641 - 480 = 161, que no se puede partir en dos enteros iguales. Lo que
        // no se negocia es que el recorte no se salga de la imagen.
        val crop = centerSquareCrop(width = 641, height = 480)

        assertEquals(480, crop.size)
        assertEquals(80, crop.left)
        assertTrue(crop.right <= 641)
        assertTrue(crop.bottom <= 480)
    }

    @Test
    fun `el recorte nunca se sale de la imagen`() {
        val sizes = listOf(1 to 1, 3 to 7, 1920 to 1080, 1081 to 1080, 4032 to 3024)

        for ((width, height) in sizes) {
            val crop = centerSquareCrop(width, height)
            assertTrue("left negativo en ${width}x$height", crop.left >= 0)
            assertTrue("top negativo en ${width}x$height", crop.top >= 0)
            assertTrue("se sale por la derecha en ${width}x$height", crop.right <= width)
            assertTrue("se sale por abajo en ${width}x$height", crop.bottom <= height)
            assertEquals(minOf(width, height), crop.size)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `un ancho de cero no se acepta`() {
        centerSquareCrop(width = 0, height = 480)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `un alto negativo no se acepta`() {
        centerSquareCrop(width = 640, height = -1)
    }

    // --- Submuestreo al decodificar ----------------------------------------

    @Test
    fun `una imagen que ya es pequena se decodifica entera`() {
        assertEquals(1, inSampleSizeFor(width = 224, height = 224, targetSize = 224))
        assertEquals(1, inSampleSizeFor(width = 300, height = 300, targetSize = 224))
    }

    @Test
    fun `una foto de doce megapixeles se decodifica submuestreada`() {
        // 4032x3024 con lado menor 3024: /8 deja 378, que sigue por encima de 224.
        assertEquals(8, inSampleSizeFor(width = 4032, height = 3024, targetSize = 224))
    }

    @Test
    fun `el submuestreo siempre es potencia de dos`() {
        val sizes = listOf(224, 400, 900, 1800, 3600, 7200)

        for (side in sizes) {
            val sample = inSampleSizeFor(side, side, targetSize = 224)
            assertTrue("$sample no es potencia de dos", sample > 0 && sample and (sample - 1) == 0)
        }
    }

    @Test
    fun `el submuestreo nunca deja el lado menor por debajo de la entrada del modelo`() {
        // Es la garantia que hace que el escalado final siga siendo una
        // reduccion: estirar la imagen para llegar a 224 seria inventar pixeles.
        val sizes = listOf(224 to 224, 640 to 480, 1920 to 1080, 4032 to 3024, 8000 to 6000)

        for ((width, height) in sizes) {
            val sample = inSampleSizeFor(width, height, targetSize = 224)
            val shortest = minOf(width, height) / sample
            assertTrue("${width}x$height con sample $sample deja $shortest", shortest >= 224)
        }
    }

    @Test
    fun `el submuestreo respeta el lado que pida el modelo`() {
        // El 224 no esta cableado: si manana el modelo pide 448, esto se adapta.
        assertEquals(8, inSampleSizeFor(width = 4032, height = 3024, targetSize = 224))
        assertEquals(4, inSampleSizeFor(width = 4032, height = 3024, targetSize = 448))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `un lado de entrada de cero no se acepta`() {
        inSampleSizeFor(width = 4032, height = 3024, targetSize = 0)
    }

    // --- Rotacion -----------------------------------------------------------

    @Test
    fun `los cuatro angulos canonicos se dejan tal cual`() {
        assertEquals(0, normalizeRotationDegrees(0))
        assertEquals(90, normalizeRotationDegrees(90))
        assertEquals(180, normalizeRotationDegrees(180))
        assertEquals(270, normalizeRotationDegrees(270))
    }

    @Test
    fun `los cuatro angulos canonicos dan cero uno dos y tres cuartos de vuelta`() {
        assertEquals(0, quarterTurnsOf(0))
        assertEquals(1, quarterTurnsOf(90))
        assertEquals(2, quarterTurnsOf(180))
        assertEquals(3, quarterTurnsOf(270))
    }

    @Test
    fun `una vuelta entera equivale a no girar`() {
        assertEquals(0, normalizeRotationDegrees(360))
        assertEquals(90, normalizeRotationDegrees(450))
        assertEquals(180, normalizeRotationDegrees(900))
    }

    @Test
    fun `los grados negativos giran en el otro sentido`() {
        // Algunas capas HAL devuelven el angulo con signo. Tratarlo como 0 seria
        // el peor resultado posible: el ave llegaria tumbada al modelo.
        assertEquals(270, normalizeRotationDegrees(-90))
        assertEquals(180, normalizeRotationDegrees(-180))
        assertEquals(90, normalizeRotationDegrees(-270))
        assertEquals(0, normalizeRotationDegrees(-360))
    }

    @Test
    fun `un angulo raro se redondea al cuarto de vuelta mas proximo`() {
        // Girar de mas es mucho mejor que no girar.
        assertEquals(90, normalizeRotationDegrees(80))
        assertEquals(90, normalizeRotationDegrees(100))
        assertEquals(180, normalizeRotationDegrees(179))
        assertEquals(0, normalizeRotationDegrees(10))
    }

    @Test
    fun `solo hace falta girar cuando el angulo no es cero`() {
        assertFalse(isRotationNeeded(0))
        assertFalse(isRotationNeeded(360))
        assertFalse(isRotationNeeded(-360))
        assertTrue(isRotationNeeded(90))
        assertTrue(isRotationNeeded(180))
        assertTrue(isRotationNeeded(270))
        assertTrue(isRotationNeeded(-90))
    }

    @Test
    fun `normalizar es idempotente`() {
        // Si no lo fuera, pasar dos veces por la conversion giraria de mas.
        for (degrees in listOf(0, 90, 180, 270, 360, -90, 45, 1000)) {
            val once = normalizeRotationDegrees(degrees)
            assertEquals(once, normalizeRotationDegrees(once))
        }
    }
}
