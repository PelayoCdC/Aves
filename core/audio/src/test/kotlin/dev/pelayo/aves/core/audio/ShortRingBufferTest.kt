package dev.pelayo.aves.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortRingBufferTest {

    @Test
    fun `no esta lleno hasta acumular capacity muestras`() {
        val buffer = ShortRingBuffer(4)
        buffer.write(shortArrayOf(1, 2, 3))
        assertFalse(buffer.isFull)
        buffer.write(shortArrayOf(4))
        assertTrue(buffer.isFull)
    }

    @Test
    fun `el snapshot devuelve las muestras en orden cronologico`() {
        val buffer = ShortRingBuffer(4)
        buffer.write(shortArrayOf(1, 2, 3, 4))

        val dest = ShortArray(4)
        buffer.snapshotInto(dest)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), dest)
    }

    @Test
    fun `al dar la vuelta conserva las ultimas capacity muestras`() {
        val buffer = ShortRingBuffer(4)
        buffer.write(shortArrayOf(1, 2, 3, 4, 5, 6))

        val dest = ShortArray(4)
        buffer.snapshotInto(dest)
        // 1 y 2 ya se han perdido: la ventana es deslizante.
        assertArrayEquals(shortArrayOf(3, 4, 5, 6), dest)
    }

    @Test
    fun `una escritura mayor que la capacidad deja solo la cola`() {
        val buffer = ShortRingBuffer(3)
        buffer.write(shortArrayOf(1, 2, 3, 4, 5, 6, 7))

        val dest = ShortArray(3)
        buffer.snapshotInto(dest)
        assertArrayEquals(shortArrayOf(5, 6, 7), dest)
    }

    @Test
    fun `escrituras troceadas producen el mismo resultado que una sola`() {
        // Importa porque AudioRecord entrega trozos de tamano arbitrario, no
        // multiplos limpios de la ventana.
        val chunked = ShortRingBuffer(5)
        chunked.write(shortArrayOf(1, 2))
        chunked.write(shortArrayOf(3))
        chunked.write(shortArrayOf(4, 5, 6, 7))

        val single = ShortRingBuffer(5)
        single.write(shortArrayOf(1, 2, 3, 4, 5, 6, 7))

        val a = ShortArray(5)
        val b = ShortArray(5)
        chunked.snapshotInto(a)
        single.snapshotInto(b)
        assertArrayEquals(b, a)
    }

    @Test
    fun `respeta el parametro length e ignora la cola del array de origen`() {
        val buffer = ShortRingBuffer(4)
        // AudioRecord.read devuelve cuantas muestras ha escrito de verdad; el
        // resto del array es basura de la lectura anterior.
        buffer.write(shortArrayOf(1, 2, 99, 99), length = 2)

        val dest = ShortArray(4)
        buffer.snapshotInto(dest)
        assertArrayEquals(shortArrayOf(0, 0, 1, 2), dest)
    }
}
