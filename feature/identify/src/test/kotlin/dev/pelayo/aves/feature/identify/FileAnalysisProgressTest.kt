package dev.pelayo.aves.feature.identify

import dev.pelayo.aves.core.audio.audioWindows
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** La barra de progreso del analisis de un fichero y la fecha de la grabacion. */
class FileAnalysisProgressTest {

    @Test
    fun `el total de ventanas es exactamente el que recorre el analisis`() {
        // Si no coinciden, la barra se queda corta o se pasa del cien por cien.
        val window = 144_000
        val hop = window / 2
        for (samples in listOf(1, window - 1, window, window + 1, window + hop, 10 * window + 7, 300 * 48_000)) {
            assertEquals(
                "con $samples muestras",
                FloatArray(samples).audioWindows(window, hop).count(),
                windowCount(samples, window, hop),
            )
        }
    }

    @Test
    fun `un fichero vacio no tiene ventanas`() {
        assertEquals(0, windowCount(0, 144_000, 72_000))
    }

    @Test
    fun `el dia elegido en el calendario no se corre por el huso horario`() {
        // El calendario devuelve la medianoche UTC del dia; en Canarias o en
        // Nueva York esa medianoche cae en el dia anterior.
        val utcMidnight = Instant.parse("2026-05-12T00:00:00Z").toEpochMilli()
        for (zone in listOf("Europe/Madrid", "Atlantic/Canary", "America/New_York", "Pacific/Auckland")) {
            val picked = Instant.ofEpochMilli(localNoonOf(utcMidnight, ZoneId.of(zone))).atZone(ZoneId.of(zone))
            assertEquals(zone, 12, picked.dayOfMonth)
            assertEquals(zone, 5, picked.monthValue)
        }
    }
}
