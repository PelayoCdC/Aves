package dev.pelayo.aves.feature.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoStackTest {

    private fun action(id: Long, decision: ReviewDecision = ReviewDecision.CONFIRMED) = ReviewAction(
        observation = PendingObservation(
            observationId = id,
            speciesId = "sp$id",
            commonName = "Especie $id",
            scientificName = "Genus specie$id",
            confidence = 0.9f,
            rawConfidence = 0.9f,
            timestamp = id,
        ),
        decision = decision,
    )

    @Test
    fun `una pila recien creada no tiene nada que deshacer`() {
        val stack = UndoStack()

        assertNull(stack.peek())
        assertNull(stack.pop())
        assertEquals(0, stack.size)
    }

    @Test
    fun `deshacer devuelve la ultima decision`() {
        val stack = UndoStack()
        val confirmed = action(1L)

        assertNull(stack.push(confirmed))
        assertEquals(confirmed, stack.peek())
        assertEquals(confirmed, stack.pop())
        assertEquals(0, stack.size)
    }

    @Test
    fun `no se puede deshacer dos veces la misma decision`() {
        val stack = UndoStack()
        stack.push(action(1L))

        assertEquals(1L, stack.pop()?.observation?.observationId)
        assertNull(stack.pop())
    }

    @Test
    fun `con capacidad uno la nueva decision desaloja a la anterior`() {
        // El desalojo es la senal de "esto ya no se puede deshacer, escribelo".
        val stack = UndoStack(capacity = 1)
        val first = action(1L)
        val second = action(2L, ReviewDecision.REJECTED)

        assertNull(stack.push(first))
        assertEquals(first, stack.push(second))
        assertEquals(second, stack.peek())
        assertEquals(1, stack.size)
    }

    @Test
    fun `con mas capacidad se deshace en orden inverso`() {
        val stack = UndoStack(capacity = 3)
        stack.push(action(1L))
        stack.push(action(2L))
        stack.push(action(3L))

        assertEquals(listOf(3L, 2L, 1L), List(3) { stack.pop()?.observation?.observationId })
    }

    @Test
    fun `solo desaloja al superar la capacidad`() {
        val stack = UndoStack(capacity = 2)

        assertNull(stack.push(action(1L)))
        assertNull(stack.push(action(2L)))
        assertEquals(1L, stack.push(action(3L))?.observation?.observationId)
        assertEquals(2, stack.size)
    }

    @Test
    fun `vaciar devuelve lo pendiente de la mas antigua a la mas reciente`() {
        // Al cerrar la pantalla eso es lo que hay que escribir en disco: una
        // decision sin deshacer es una decision tomada, no una perdida.
        val stack = UndoStack(capacity = 3)
        stack.push(action(1L))
        stack.push(action(2L))

        assertEquals(listOf(1L, 2L), stack.drain().map { it.observation.observationId })
        assertEquals(0, stack.size)
        assertNull(stack.peek())
    }

    @Test
    fun `vaciar dos veces no duplica escrituras`() {
        val stack = UndoStack()
        stack.push(action(1L))
        stack.drain()

        assertTrue(stack.drain().isEmpty())
    }

    @Test
    fun `lo ya deshecho no aparece al vaciar`() {
        val stack = UndoStack(capacity = 2)
        stack.push(action(1L))
        stack.push(action(2L))
        stack.pop()

        assertEquals(listOf(1L), stack.drain().map { it.observation.observationId })
    }

    @Test
    fun `conserva que decision fue, no solo cual observacion`() {
        val stack = UndoStack()
        stack.push(action(1L, ReviewDecision.REJECTED))

        assertEquals(ReviewDecision.REJECTED, stack.pop()?.decision)
    }

    @Test
    fun `una capacidad de cero no tiene sentido`() {
        assertThrows(IllegalArgumentException::class.java) { UndoStack(capacity = 0) }
    }
}
