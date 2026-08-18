package dev.pelayo.aves.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las lineas explicativas.
 *
 * Se prueban porque son la parte de la pantalla que hace que un ajuste llamado
 * "influencia del contexto" signifique algo: si dos posiciones distintas del
 * deslizador dijeran lo mismo, el control no estaria explicado, solo decorado.
 */
class SettingsCopyTest {

    // --- Porcentajes --------------------------------------------------------

    @Test
    fun `el porcentaje se redondea al entero`() {
        assertEquals("0 %", percentLabel(0f))
        assertEquals("50 %", percentLabel(0.5f))
        assertEquals("100 %", percentLabel(1f))
        assertEquals("37 %", percentLabel(0.367f))
    }

    @Test
    fun `el porcentaje satura y aguanta un no-numero`() {
        assertEquals("100 %", percentLabel(4f))
        assertEquals("0 %", percentLabel(-1f))
        assertEquals("0 %", percentLabel(Float.NaN))
    }

    // --- Tramos de la influencia del contexto -------------------------------

    @Test
    fun `los tramos del contexto cubren el rango entero`() {
        assertEquals(ContextInfluenceTier.OFF, ContextInfluenceTier.of(0f))
        assertEquals(ContextInfluenceTier.LIGHT, ContextInfluenceTier.of(0.2f))
        assertEquals(ContextInfluenceTier.BALANCED, ContextInfluenceTier.of(0.5f))
        assertEquals(ContextInfluenceTier.DOMINANT, ContextInfluenceTier.of(1f))
    }

    @Test
    fun `los umbrales del contexto son inclusivos por abajo`() {
        assertEquals(
            ContextInfluenceTier.LIGHT,
            ContextInfluenceTier.of(ContextInfluenceTier.LIGHT_THRESHOLD),
        )
        assertEquals(
            ContextInfluenceTier.BALANCED,
            ContextInfluenceTier.of(ContextInfluenceTier.BALANCED_THRESHOLD),
        )
        assertEquals(
            ContextInfluenceTier.DOMINANT,
            ContextInfluenceTier.of(ContextInfluenceTier.DOMINANT_THRESHOLD),
        )
    }

    @Test
    fun `el valor por defecto cae en el tramo equilibrado`() {
        assertEquals(
            ContextInfluenceTier.BALANCED,
            ContextInfluenceTier.of(AvesSettings.DEFAULT_CONTEXT_INFLUENCE),
        )
    }

    // --- Linea de la influencia del contexto --------------------------------

    @Test
    fun `la linea del contexto empieza por el porcentaje`() {
        assertTrue(contextInfluenceLine(0.5f).startsWith("50 % · "))
    }

    @Test
    fun `a cero la linea del contexto explica el caso de la grabacion ajena`() {
        // Es el caso de uso concreto que justifica que el 0 sea alcanzable.
        val line = contextInfluenceLine(0f)
        assertTrue(line.contains("no interviene"))
        assertTrue(line.contains("otro sitio"))
    }

    @Test
    fun `al maximo la linea del contexto avisa de que una rareza puede perderse`() {
        // Si el usuario sube esto al tope tiene que saber que paga con divagantes.
        assertTrue(contextInfluenceLine(1f).contains("rareza"))
    }

    @Test
    fun `cada tramo del contexto dice algo distinto`() {
        val lines = listOf(0f, 0.2f, 0.5f, 1f).map(::contextInfluenceLine)
        assertEquals(lines.size, lines.distinct().size)
    }

    @Test
    fun `la linea del contexto valida el valor que recibe`() {
        // Un valor fuera de rango se explica como su equivalente saturado, no
        // como "300 %".
        assertEquals(contextInfluenceLine(1f), contextInfluenceLine(3f))
    }

    // --- Linea del umbral de confianza --------------------------------------

    @Test
    fun `los tramos del umbral se separan donde toca`() {
        assertEquals(ConfidenceGate.PERMISSIVE, ConfidenceGate.of(0.1f))
        assertEquals(ConfidenceGate.BALANCED, ConfidenceGate.of(ConfidenceGate.BALANCED_THRESHOLD))
        assertEquals(ConfidenceGate.STRICT, ConfidenceGate.of(ConfidenceGate.STRICT_THRESHOLD))
    }

    @Test
    fun `la linea del umbral empieza por el porcentaje y cambia por tramo`() {
        assertTrue(confidenceThresholdLine(0.8f).startsWith("80 % · "))
        assertNotEquals(confidenceThresholdLine(0.1f), confidenceThresholdLine(0.8f))
    }

    @Test
    fun `un umbral bajo avisa del ruido y uno alto de lo que se pierde`() {
        assertTrue(confidenceThresholdLine(0.05f).contains("ruido"))
        assertTrue(confidenceThresholdLine(0.95f).contains("escaparán"))
    }

    // --- Corte de escucha ---------------------------------------------------

    @Test
    fun `el corte se escribe en minutos y en horas`() {
        assertEquals("Sin corte", cutoffLabel(0))
        assertEquals("45 min", cutoffLabel(45))
        assertEquals("1 hora", cutoffLabel(60))
        assertEquals("2 horas", cutoffLabel(120))
        assertEquals("1 h 30 min", cutoffLabel(90))
    }

    @Test
    fun `sin corte la linea avisa del gasto de bateria`() {
        // Es el precio de esa opcion y hay que decirlo donde se elige.
        val line = listeningCutoffLine(AvesSettings.NO_CUTOFF)
        assertTrue(line.startsWith("Sin corte · "))
        assertTrue(line.contains("batería"))
    }

    @Test
    fun `con corte la linea lleva la duracion delante`() {
        assertTrue(listeningCutoffLine(30).startsWith("30 min · "))
        assertTrue(listeningCutoffLine(60).startsWith("1 hora · "))
    }

    @Test
    fun `la linea del corte ajusta el valor al escalon`() {
        assertEquals(listeningCutoffLine(35), listeningCutoffLine(33))
    }

    // --- Recuentos ----------------------------------------------------------

    @Test
    fun `los millares llevan punto`() {
        assertEquals("0", groupThousands(0))
        assertEquals("42", groupThousands(42))
        assertEquals("999", groupThousands(999))
        assertEquals("1.000", groupThousands(1000))
        assertEquals("6.419", groupThousands(6419))
        assertEquals("1.234.567", groupThousands(1234567))
    }

    @Test
    fun `una sola especie va en singular`() {
        assertEquals("1 especie", speciesCountLabel(1))
        assertEquals("2 especies", speciesCountLabel(2))
        assertEquals("6.419 especies", speciesCountLabel(6419))
    }

    // --- Tamaño -------------------------------------------------------------

    @Test
    fun `el tamaño usa unidades decimales`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("999 B", formatBytes(999))
        assertEquals("1 kB", formatBytes(1_000))
        assertEquals("12 kB", formatBytes(12_345))
        assertEquals("1,0 MB", formatBytes(1_000_000))
        assertEquals("12,4 MB", formatBytes(12_400_000))
        assertEquals("2,5 GB", formatBytes(2_500_000_000L))
    }

    @Test
    fun `el separador decimal es la coma pase lo que pase con el Locale`() {
        // Con String-format esto dependeria de la configuracion del dispositivo
        // y la prueba pasaria o fallaria segun quien la ejecute.
        assertTrue(formatBytes(3_300_000).contains(','))
        assertFalse(formatBytes(3_300_000).contains('.'))
    }

    @Test
    fun `redondear hacia arriba no deja un decimal de diez`() {
        // 999.960.000 B redondea a 1000,0 MB, que hay que subir a la unidad.
        assertEquals("1000,0 MB", formatBytes(999_960_000L))
    }

    @Test
    fun `un tamaño negativo no revienta`() {
        assertEquals("0 B", formatBytes(-1))
    }

    // --- Linea de la coleccion ----------------------------------------------

    @Test
    fun `la linea de la coleccion junta especies y tamaño`() {
        assertEquals(
            "42 especies en tu colección · 12,4 MB en recortes de audio",
            collectionSizeLine(speciesCount = 42, bytes = 12_400_000L),
        )
    }

    @Test
    fun `sin coleccion se dice, no se enseña un cero`() {
        assertEquals(
            "Todavía no has confirmado ninguna especie",
            collectionSizeLine(speciesCount = 0, bytes = null),
        )
    }

    @Test
    fun `sin nada que contar no hay linea`() {
        // La pantalla la omite en vez de dibujar un hueco vacio.
        assertNull(collectionSizeLine(speciesCount = null, bytes = null))
    }

    @Test
    fun `cada dato puede faltar por separado`() {
        assertEquals(
            "3,0 MB en recortes de audio",
            collectionSizeLine(speciesCount = null, bytes = 3_000_000L),
        )
        assertEquals(
            "1 especie en tu colección",
            collectionSizeLine(speciesCount = 1, bytes = null),
        )
    }
}
