package dev.pelayo.aves.core.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El corte por tiempo es la unica salvaguarda contra una escucha olvidada, asi
 * que su aritmetica no puede depender de probarla dejando el movil toda la
 * noche. Por eso vive en una clase pura y por eso esta aqui.
 */
class ListeningDeadlineTest {

    private val start = 1_000_000L

    @Test
    fun `sin duracion pedida usa el limite por defecto`() {
        val deadline = ListeningDeadline(start)
        assertEquals(ListeningDeadline.DEFAULT_DURATION_MILLIS, deadline.durationMillis)
        assertEquals(start + ListeningDeadline.DEFAULT_DURATION_MILLIS, deadline.expiresAtMillis)
    }

    @Test
    fun `respeta una duracion dentro del rango`() {
        val tenMinutes = 10L * 60_000L
        assertEquals(tenMinutes, ListeningDeadline(start, tenMinutes).durationMillis)
    }

    @Test
    fun `recorta una duracion excesiva en vez de aceptarla`() {
        // Es el fallo que justifica toda la clase: 24 h de microfono abierto no
        // es una intencion, es un descuido.
        val aDay = 24L * 60L * 60_000L
        assertEquals(
            ListeningDeadline.MAX_DURATION_MILLIS,
            ListeningDeadline(start, aDay).durationMillis,
        )
    }

    @Test
    fun `recorta una duracion ridicula hacia arriba`() {
        // Un extra a cero no debe convertirse en "no escuchar nunca".
        assertEquals(
            ListeningDeadline.MIN_DURATION_MILLIS,
            ListeningDeadline(start, 0L).durationMillis,
        )
    }

    @Test
    fun `una duracion negativa tampoco se acepta`() {
        assertEquals(
            ListeningDeadline.MIN_DURATION_MILLIS,
            ListeningDeadline(start, -5_000L).durationMillis,
        )
    }

    @Test
    fun `lo que queda se descuenta segun avanza el reloj`() {
        val deadline = ListeningDeadline(start, 10L * 60_000L)

        assertEquals(10L * 60_000L, deadline.remainingMillis(start))
        assertEquals(4L * 60_000L, deadline.remainingMillis(start + 6L * 60_000L))
    }

    @Test
    fun `lo que queda nunca es negativo`() {
        // Se pasa tal cual a withTimeoutOrNull, y un negativo ahi seria una
        // escucha que ni siquiera llega a arrancar el microfono.
        val deadline = ListeningDeadline(start, 60_000L)
        assertEquals(0L, deadline.remainingMillis(start + 10L * 60_000L))
    }

    @Test
    fun `caduca justo al llegar al instante de corte`() {
        val deadline = ListeningDeadline(start, 60_000L)

        assertFalse(deadline.hasExpired(start))
        assertFalse(deadline.hasExpired(deadline.expiresAtMillis - 1))
        assertTrue(deadline.hasExpired(deadline.expiresAtMillis))
        assertTrue(deadline.hasExpired(deadline.expiresAtMillis + 1))
    }

    @Test
    fun `el limite por defecto esta dentro del rango admitido`() {
        // Protege de un cambio de constantes que dejase el defecto fuera de
        // rango, que se manifestaria como "la escucha dura otra cosa" sin error.
        assertTrue(
            ListeningDeadline.DEFAULT_DURATION_MILLIS >= ListeningDeadline.MIN_DURATION_MILLIS,
        )
        assertTrue(
            ListeningDeadline.DEFAULT_DURATION_MILLIS <= ListeningDeadline.MAX_DURATION_MILLIS,
        )
    }
}
