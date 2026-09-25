package dev.pelayo.aves.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validacion y saturacion de los rangos.
 *
 * Lo que se prueba aqui no es aritmetica: es que ningun valor imposible pueda
 * llegar al clasificador desde el disco, que es por donde entraria un ajuste
 * escrito por una version anterior de la app.
 */
class SettingsPreferencesTest {

    // --- Influencia del contexto -------------------------------------------

    @Test
    fun `la confianza no se ensena de fabrica`() {
        // Estilo Merlin: nombres, no numeros, hasta que el usuario lo pida.
        assertEquals(false, AvesSettings().showConfidence)
        assertEquals(true, AvesSettings(showConfidence = true).sanitized().showConfidence)
    }

    @Test
    fun `la influencia del contexto por defecto es la mitad`() {
        // A la mitad y no al maximo: el prior es una estadistica, no una verdad.
        assertEquals(0.5f, AvesSettings().contextInfluence, 1e-6f)
    }

    @Test
    fun `la influencia del contexto se satura en los dos extremos`() {
        assertEquals(0f, AvesSettings.sanitizeContextInfluence(-3f), 1e-6f)
        assertEquals(1f, AvesSettings.sanitizeContextInfluence(42f), 1e-6f)
    }

    @Test
    fun `los extremos de la influencia del contexto son alcanzables`() {
        // El 0 tiene un caso de uso real: analizar una grabacion de otro sitio.
        assertEquals(0f, AvesSettings.sanitizeContextInfluence(0f), 1e-6f)
        assertEquals(1f, AvesSettings.sanitizeContextInfluence(1f), 1e-6f)
    }

    @Test
    fun `un valor intermedio de influencia se respeta tal cual`() {
        assertEquals(0.37f, AvesSettings.sanitizeContextInfluence(0.37f), 1e-6f)
    }

    @Test
    fun `un no-numero vuelve al valor por defecto`() {
        // Saturar un NaN lo dejaria pasar: coerceIn no lo arregla.
        assertEquals(
            AvesSettings.DEFAULT_CONTEXT_INFLUENCE,
            AvesSettings.sanitizeContextInfluence(Float.NaN),
            1e-6f,
        )
        assertEquals(
            AvesSettings.DEFAULT_CONFIDENCE_THRESHOLD,
            AvesSettings.sanitizeConfidenceThreshold(Float.NaN),
            1e-6f,
        )
    }

    @Test
    fun `los infinitos se saturan`() {
        assertEquals(1f, AvesSettings.sanitizeContextInfluence(Float.POSITIVE_INFINITY), 1e-6f)
        assertEquals(0f, AvesSettings.sanitizeContextInfluence(Float.NEGATIVE_INFINITY), 1e-6f)
    }

    // --- Umbral de confianza -----------------------------------------------

    @Test
    fun `el umbral de confianza nunca llega a cero ni a uno`() {
        // A 0 la lista serian miles de especies de ruido; a 1 no pasaria nada
        // nunca. Un extremo inutil solo sirve para que parezca que la app falla.
        assertEquals(0.05f, AvesSettings.sanitizeConfidenceThreshold(0f), 1e-6f)
        assertEquals(0.95f, AvesSettings.sanitizeConfidenceThreshold(1f), 1e-6f)
    }

    @Test
    fun `el umbral de confianza respeta los valores del rango`() {
        assertEquals(0.5f, AvesSettings.sanitizeConfidenceThreshold(0.5f), 1e-6f)
        assertEquals(0.05f, AvesSettings.sanitizeConfidenceThreshold(0.05f), 1e-6f)
        assertEquals(0.95f, AvesSettings.sanitizeConfidenceThreshold(0.95f), 1e-6f)
    }

    // --- Corte de escucha ---------------------------------------------------

    @Test
    fun `el cero significa sin corte y se conserva`() {
        assertEquals(AvesSettings.NO_CUTOFF, AvesSettings.sanitizeListeningCutoff(0))
    }

    @Test
    fun `un corte negativo vuelve al valor por defecto y no a sin corte`() {
        // Saturar hacia abajo convertiria un dato corrupto en una escucha
        // indefinida, que es justo el fallo que el corte existe para evitar.
        assertEquals(
            AvesSettings.DEFAULT_LISTENING_CUTOFF_MINUTES,
            AvesSettings.sanitizeListeningCutoff(-1),
        )
    }

    @Test
    fun `el corte se ajusta al escalon mas cercano`() {
        assertEquals(30, AvesSettings.sanitizeListeningCutoff(31))
        assertEquals(35, AvesSettings.sanitizeListeningCutoff(33))
        assertEquals(45, AvesSettings.sanitizeListeningCutoff(45))
    }

    @Test
    fun `un corte de un minuto no se redondea a sin corte`() {
        // Redondear 1 a 0 daria exactamente lo contrario de lo que se pidio.
        assertEquals(AvesSettings.LISTENING_CUTOFF_STEP_MINUTES, AvesSettings.sanitizeListeningCutoff(1))
        assertEquals(AvesSettings.LISTENING_CUTOFF_STEP_MINUTES, AvesSettings.sanitizeListeningCutoff(2))
    }

    @Test
    fun `el corte se satura en el maximo`() {
        assertEquals(120, AvesSettings.sanitizeListeningCutoff(999))
        assertEquals(120, AvesSettings.sanitizeListeningCutoff(120))
    }

    @Test
    fun `todos los escalones sobreviven a la validacion`() {
        var minutes = AvesSettings.LISTENING_CUTOFF_STEP_MINUTES
        while (minutes <= AvesSettings.LISTENING_CUTOFF_RANGE.last) {
            assertEquals(minutes, AvesSettings.sanitizeListeningCutoff(minutes))
            minutes += AvesSettings.LISTENING_CUTOFF_STEP_MINUTES
        }
    }

    // --- El objeto completo -------------------------------------------------

    @Test
    fun `sanear arregla los cuatro campos a la vez`() {
        val saneado = AvesSettings(
            contextInfluence = 9f,
            locationDisabled = true,
            confidenceThreshold = -2f,
            listeningCutoffMinutes = 998,
        ).sanitized()

        assertEquals(1f, saneado.contextInfluence, 1e-6f)
        assertEquals(0.05f, saneado.confidenceThreshold, 1e-6f)
        assertEquals(120, saneado.listeningCutoffMinutes)
        // El interruptor no tiene rango que saturar: se pasa intacto.
        assertTrue(saneado.locationDisabled)
    }

    @Test
    fun `sanear es idempotente`() {
        val once = AvesSettings(
            contextInfluence = 0.73f,
            confidenceThreshold = 0.42f,
            listeningCutoffMinutes = 17,
        ).sanitized()

        assertEquals(once, once.sanitized())
    }

    @Test
    fun `sanear unos ajustes ya validos no cambia nada`() {
        val defaults = AvesSettings()
        assertEquals(defaults, defaults.sanitized())
    }

    @Test
    fun `los valores por defecto estan dentro de su rango`() {
        // Si esto fallara, una instalacion nueva arrancaria ya invalida.
        assertTrue(
            AvesSettings.DEFAULT_CONTEXT_INFLUENCE in AvesSettings.CONTEXT_INFLUENCE_RANGE,
        )
        assertTrue(
            AvesSettings.DEFAULT_CONFIDENCE_THRESHOLD in AvesSettings.CONFIDENCE_THRESHOLD_RANGE,
        )
        assertTrue(
            AvesSettings.DEFAULT_LISTENING_CUTOFF_MINUTES in AvesSettings.LISTENING_CUTOFF_RANGE,
        )
        assertEquals(
            0,
            AvesSettings.DEFAULT_LISTENING_CUTOFF_MINUTES % AvesSettings.LISTENING_CUTOFF_STEP_MINUTES,
        )
    }

    @Test
    fun `el rango del contexto sigue siendo de cero a uno`() {
        // Lo asume el deslizador y tambien ObservationContext.contextInfluence.
        assertEquals(0f, AvesSettings.CONTEXT_INFLUENCE_RANGE.start, 1e-6f)
        assertEquals(1f, AvesSettings.CONTEXT_INFLUENCE_RANGE.endInclusive, 1e-6f)
    }
}
