package dev.pelayo.aves.core.ml.birdnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * Toda la logica del prior que no necesita el interprete. Lo que si lo necesita
 * (que el .tflite cargue y devuelva 6.522 valores) solo se puede comprobar en un
 * test instrumentado.
 */
class BirdNetPriorMathTest {

    private val utc: ZoneId = ZoneOffset.UTC

    private fun millisAt(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(utc).toInstant().toEpochMilli()

    @Test
    fun `el ano del meta-modelo tiene 48 semanas, no 52`() {
        assertEquals(1, BirdNetPriorMath.weekOfYear(1))
        assertEquals(48, BirdNetPriorMath.weekOfYear(366))
        // El 31 de diciembre de un ano comun tambien cae en la ultima semana: es
        // el motivo de dividir siempre entre 366 y no entre 365.
        assertEquals(48, BirdNetPriorMath.weekOfYear(365))
    }

    @Test
    fun `la semana nunca se sale del rango aunque el dia del ano sea absurdo`() {
        for (dayOfYear in 1..366) {
            val week = BirdNetPriorMath.weekOfYear(dayOfYear)
            assertTrue("dia $dayOfYear -> semana $week", week in 1..48)
        }
        // Defensa contra un dia del ano imposible: mejor saturar que indexar mal.
        assertEquals(1, BirdNetPriorMath.weekOfYear(0))
        assertEquals(48, BirdNetPriorMath.weekOfYear(999))
    }

    @Test
    fun `la semana crece de forma monotona a lo largo del ano`() {
        var previous = 0
        for (dayOfYear in 1..366) {
            val week = BirdNetPriorMath.weekOfYear(dayOfYear)
            assertTrue("retrocede en el dia $dayOfYear", week >= previous)
            previous = week
        }
        assertEquals(48, previous)
    }

    @Test
    fun `traduce un instante a semana con la zona horaria dada`() {
        assertEquals(1, BirdNetPriorMath.weekOfYear(millisAt(2026, 1, 1), utc))
        assertEquals(48, BirdNetPriorMath.weekOfYear(millisAt(2026, 12, 31), utc))
        // Mitad de junio: dia 166 -> ceil(166 * 48 / 366) = 22.
        assertEquals(22, BirdNetPriorMath.weekOfYear(millisAt(2026, 6, 15), utc))
    }

    @Test
    fun `weekMeta cierra el ciclo entre la semana 48 y la semana 1`() {
        // cos(360) + 1 = 2, el maximo exacto.
        assertEquals(2f, BirdNetPriorMath.weekMeta(48), 1e-6f)
        // cos(7,5) + 1: casi el maximo, porque la semana 1 esta pegada a la 48.
        assertEquals(1.9914449f, BirdNetPriorMath.weekMeta(1), 1e-5f)
        // Medio ano despues, el minimo: cos(180) + 1 = 0.
        assertEquals(0f, BirdNetPriorMath.weekMeta(24), 1e-6f)

        // El salto de fin de ano tiene que ser minusculo comparado con medio ano.
        // Es toda la gracia de codificar el calendario como un ciclo: para un ave
        // el 31 de diciembre y el 1 de enero son la misma epoca.
        val saltoDeAno = abs(BirdNetPriorMath.weekMeta(48) - BirdNetPriorMath.weekMeta(1))
        val medioAno = abs(BirdNetPriorMath.weekMeta(48) - BirdNetPriorMath.weekMeta(24))
        assertTrue(saltoDeAno < medioAno)
    }

    @Test
    fun `weekMeta se mantiene en el rango que espera el modelo`() {
        for (week in 1..BirdNetPriorMath.WEEKS_IN_YEAR) {
            val value = BirdNetPriorMath.weekMeta(week)
            assertTrue("semana $week -> $value", value in 0f..2f)
        }
    }

    @Test
    fun `la combinacion pesa igual la semana actual y el maximo anual`() {
        assertEquals(0.5f, BirdNetPriorMath.combineWithAnnualMax(0f, 1f), 1e-6f)
        assertEquals(0.5f, BirdNetPriorMath.combineWithAnnualMax(1f, 0f), 1e-6f)
        assertEquals(1f, BirdNetPriorMath.combineWithAnnualMax(1f, 1f), 1e-6f)
        assertEquals(0f, BirdNetPriorMath.combineWithAnnualMax(0f, 0f), 1e-6f)
    }

    @Test
    fun `los cuatro tramos de la discretizacion`() {
        assertEquals(1f, BirdNetPriorMath.multiplierFor(0.5f), 0f)
        assertEquals(1f, BirdNetPriorMath.multiplierFor(0.01f), 0f)
        assertEquals(0.8f, BirdNetPriorMath.multiplierFor(0.009f), 0f)
        assertEquals(0.8f, BirdNetPriorMath.multiplierFor(0.008f), 0f)
        assertEquals(0.5f, BirdNetPriorMath.multiplierFor(0.005f), 0f)
        assertEquals(0.5f, BirdNetPriorMath.multiplierFor(0.001f), 0f)
        assertEquals(0.05f, BirdNetPriorMath.multiplierFor(0.0009f), 0f)
    }

    @Test
    fun `la discretizacion no invierte el orden`() {
        var previous = 0f
        var probability = 0f
        while (probability <= 0.02f) {
            val multiplier = BirdNetPriorMath.multiplierFor(probability)
            assertTrue("baja en $probability", multiplier >= previous)
            previous = multiplier
            probability += 0.0001f
        }
    }

    @Test
    fun `ningun multiplicador vale exactamente cero`() {
        // La regla que no se puede romper. Con un cero la especie deja de ser
        // detectable por construccion y las primeras victimas serian las
        // rarezas, que es lo mas interesante que puede pasar en esta app.
        assertEquals(0.05f, BirdNetPriorMath.multiplierFor(0f), 0f)
        assertEquals(0.05f, BirdNetPriorMath.multiplierFor(-1f), 0f)
        var probability = 0f
        while (probability <= 1f) {
            val multiplier = BirdNetPriorMath.multiplierFor(probability)
            assertTrue("probabilidad $probability -> $multiplier", multiplier >= 0.05f)
            probability += 0.0005f
        }
    }

    @Test
    fun `el maximo anual rescata a una migratoria ausente esta semana`() {
        // Caso real: un papamoscas que aqui solo esta en primavera y que hoy, en
        // pleno invierno, el meta-modelo da por ausente. Con solo la semana
        // actual caeria al suelo; con el maximo anual sigue siendo plausible, que
        // es justo lo que hace falta para detectar una llegada temprana.
        val ausenteAhora = 0f
        val presenteEnPaso = 0.5f
        val combinado = BirdNetPriorMath.combineWithAnnualMax(ausenteAhora, presenteEnPaso)

        assertEquals(1f, BirdNetPriorMath.multiplierFor(combinado), 0f)
    }

    @Test
    fun `una especie que nunca esta aqui cae al suelo pero no a cero`() {
        val combinado = BirdNetPriorMath.combineWithAnnualMax(0f, 0.0005f)
        val multiplicador = BirdNetPriorMath.multiplierFor(combinado)

        assertEquals(BirdNetPriorMath.FLOOR_MULTIPLIER, multiplicador, 0f)
        assertTrue(multiplicador > 0f)
    }

    @Test
    fun `redondea las coordenadas a la resolucion de la cache`() {
        assertEquals(43.36, BirdNetPriorMath.roundCoordinate(43.3614), 1e-9)
        assertEquals(-5.85, BirdNetPriorMath.roundCoordinate(-5.8494), 1e-9)
        // Dos puntos a menos de un kilometro comparten clave y por tanto barrido.
        assertEquals(
            BirdNetPriorMath.roundCoordinate(43.3612),
            BirdNetPriorMath.roundCoordinate(43.3617),
            0.0,
        )
    }

    @Test
    fun `construye la tabla de multiplicadores por speciesId`() {
        val ids = listOf("erithacus_rubecula", "ficedula_hypoleuca", "spheniscus_demersus")
        val weekly = floatArrayOf(0.5f, 0f, 0f)
        val annualMax = floatArrayOf(0.6f, 0.4f, 0f)

        val multipliers = BirdNetPriorMath.buildMultipliers(ids, weekly, annualMax)

        assertEquals(1f, multipliers.getValue("erithacus_rubecula"), 0f)
        // Migratoria rescatada por el maximo anual.
        assertEquals(1f, multipliers.getValue("ficedula_hypoleuca"), 0f)
        // Un pinguino en Asturias: al suelo, pero no a cero.
        assertEquals(0.05f, multipliers.getValue("spheniscus_demersus"), 0f)
    }

    @Test
    fun `varias clases del mismo speciesId se quedan con el multiplicador mas alto`() {
        // BirdNET separa subespecies y grupos que eBird agrupa: si alguna de esas
        // formas es esperable aqui, la especie lo es.
        val ids = listOf("parus_major", "parus_major")
        val multipliers = BirdNetPriorMath.buildMultipliers(
            speciesIds = ids,
            weekly = floatArrayOf(0f, 0.5f),
            annualMax = floatArrayOf(0f, 0.5f),
        )

        assertEquals(1, multipliers.size)
        assertEquals(1f, multipliers.getValue("parus_major"), 0f)
    }

    @Test
    fun `revienta si la salida del meta-modelo no cubre las etiquetas`() {
        // Un desajuste aqui significaria multiplicadores desplazados: cada
        // especie recibiria el prior de otra, en silencio.
        val error = runCatching {
            BirdNetPriorMath.buildMultipliers(
                speciesIds = listOf("a", "b"),
                weekly = floatArrayOf(0.5f),
                annualMax = floatArrayOf(0.5f),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}

class MapSpeciesPriorTest {

    private val prior = MapSpeciesPrior(
        mapOf("erithacus_rubecula" to 1f, "spheniscus_demersus" to 0.05f),
    )

    @Test
    fun `devuelve el multiplicador de una especie conocida`() {
        assertEquals(1f, prior["erithacus_rubecula"], 0f)
        assertEquals(0.05f, prior["spheniscus_demersus"], 0f)
    }

    @Test
    fun `una especie desconocida no se penaliza`() {
        // El prior solo sabe de las 6.522 clases del modelo. Si el clasificador
        // pregunta por otra cosa, no opinar es devolver 1, no devolver 0.
        assertEquals(1f, prior["especie_que_no_existe"], 0f)
        assertEquals(1f, prior[""], 0f)
    }

    @Test
    fun `nunca devuelve cero`() {
        assertTrue(prior["spheniscus_demersus"] > 0f)
        assertTrue(prior["desconocida"] > 0f)
    }
}
