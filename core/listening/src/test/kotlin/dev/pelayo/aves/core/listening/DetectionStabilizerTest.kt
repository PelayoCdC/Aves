package dev.pelayo.aves.core.listening

import dev.pelayo.aves.core.ml.api.ClassificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Copia de la bateria de `:feature:identify`. Se duplica junto con la clase: si
 * alguien toca la histeresis de aqui pensando que es solo "la del servicio",
 * estos tests son los que avisan de que ha cambiado lo que ve el usuario.
 */
class DetectionStabilizerTest {

    private fun result(speciesId: String, score: Float) = ClassificationResult(
        speciesId = speciesId,
        rawLabel = "$speciesId-label",
        labelIndex = 0,
        rawScore = score,
        adjustedScore = score,
    )

    @Test
    fun `una sola ventana no confirma nada`() {
        val stabilizer = DetectionStabilizer()
        assertTrue(stabilizer.update(listOf(result("mirlo", 0.9f))).isEmpty())
    }

    @Test
    fun `confirma tras tres ventanas consecutivas`() {
        val stabilizer = DetectionStabilizer()
        stabilizer.update(listOf(result("mirlo", 0.9f)))
        stabilizer.update(listOf(result("mirlo", 0.9f)))
        val confirmed = stabilizer.update(listOf(result("mirlo", 0.9f)))

        assertEquals(1, confirmed.size)
        assertEquals("mirlo", confirmed[0].speciesId)
    }

    @Test
    fun `una racha interrumpida vuelve a empezar`() {
        val stabilizer = DetectionStabilizer()
        stabilizer.update(listOf(result("mirlo", 0.9f)))
        stabilizer.update(listOf(result("mirlo", 0.9f)))
        stabilizer.update(emptyList())
        val afterGap = stabilizer.update(listOf(result("mirlo", 0.9f)))

        assertTrue("el hueco deberia reiniciar la racha", afterGap.isEmpty())
    }

    @Test
    fun `una puntuacion baja no cuenta para confirmar`() {
        // 0,2 esta por debajo del umbral de confirmacion (0,35, el que recomienda
        // el propio BirdNET). El valor original de este test era 0,5, que valia
        // cuando el umbral era 0,65: al bajarlo, 0,5 paso a ser una deteccion
        // perfectamente valida y el test dejo de comprobar lo que decia su
        // nombre.
        val stabilizer = DetectionStabilizer()
        repeat(5) { stabilizer.update(listOf(result("mirlo", 0.2f))) }
        assertTrue(stabilizer.update(listOf(result("mirlo", 0.2f))).isEmpty())
    }

    @Test
    fun `una deteccion moderada si confirma tras varias ventanas`() {
        // El caso que la primera prueba de campo real dejo al descubierto: un ave
        // lejana con trafico de fondo rara vez pasa de 0,65, y con el umbral
        // antiguo no se confirmaba NADA. Con 0,35 sostenido tres ventanas si.
        val stabilizer = DetectionStabilizer()
        stabilizer.update(listOf(result("mirlo", 0.45f)))
        stabilizer.update(listOf(result("mirlo", 0.45f)))
        val confirmed = stabilizer.update(listOf(result("mirlo", 0.45f)))

        assertEquals(1, confirmed.size)
        assertEquals("mirlo", confirmed[0].speciesId)
    }

    @Test
    fun `una vez confirmada sobrevive con puntuacion mas baja`() {
        // Es el caso real de un ave que canta a rachas: entre trino y trino la
        // confianza baja, y no queremos que desaparezca de la pantalla.
        val stabilizer = DetectionStabilizer()
        repeat(3) { stabilizer.update(listOf(result("mirlo", 0.9f))) }

        val still = stabilizer.update(listOf(result("mirlo", 0.45f)))
        assertEquals(1, still.size)
    }

    @Test
    fun `se olvida tras suficientes ventanas sin oirla`() {
        val stabilizer = DetectionStabilizer(forgetAfterMisses = 2)
        repeat(3) { stabilizer.update(listOf(result("mirlo", 0.9f))) }

        stabilizer.update(emptyList())
        val gone = stabilizer.update(emptyList())
        assertTrue(gone.isEmpty())
    }

    @Test
    fun `conserva la mejor puntuacion vista y no la ultima`() {
        val stabilizer = DetectionStabilizer()
        stabilizer.update(listOf(result("mirlo", 0.95f)))
        stabilizer.update(listOf(result("mirlo", 0.70f)))
        val confirmed = stabilizer.update(listOf(result("mirlo", 0.68f)))

        assertEquals(0.95f, confirmed[0].score, 1e-6f)
    }

    @Test
    fun `admite varias especies a la vez ordenadas por puntuacion`() {
        // BirdNET es multi-etiqueta: en el mismo audio puede haber dos aves.
        val stabilizer = DetectionStabilizer()
        repeat(3) {
            stabilizer.update(listOf(result("mirlo", 0.8f), result("petirrojo", 0.9f)))
        }
        val confirmed = stabilizer.update(
            listOf(result("mirlo", 0.8f), result("petirrojo", 0.9f)),
        )

        assertEquals(listOf("petirrojo", "mirlo"), confirmed.map { it.speciesId })
    }

    @Test
    fun `ignora candidatos sin especie mapeada`() {
        val stabilizer = DetectionStabilizer()
        val unmapped = ClassificationResult(
            speciesId = null,
            rawLabel = "Noise_Noise",
            labelIndex = 3927,
            rawScore = 0.99f,
            adjustedScore = 0.99f,
        )
        repeat(5) { stabilizer.update(listOf(unmapped)) }
        assertTrue(stabilizer.update(listOf(unmapped)).isEmpty())
    }

    @Test
    fun `reset deja el estabilizador como recien creado`() {
        // Importa en el servicio: una escucha nueva no puede heredar las rachas
        // de la anterior, o confirmaria en la primera ventana.
        val stabilizer = DetectionStabilizer()
        repeat(3) { stabilizer.update(listOf(result("mirlo", 0.9f))) }
        stabilizer.reset()

        assertTrue(stabilizer.update(listOf(result("mirlo", 0.9f))).isEmpty())
    }
}
