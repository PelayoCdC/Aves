package dev.pelayo.aves.core.ml.birdnet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pelayo.aves.core.ml.api.ObservationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.GregorianCalendar

/**
 * Comprueba que el meta-modelo geo-temporal HACE algo.
 *
 * El riesgo concreto: si su salida fuesen logits en vez de probabilidades, todos
 * los valores quedarian en torno a 0.5, todas las especies caerian en el tramo
 * de multiplicador 1.0 y el prior **no filtraria nada**. No habria excepcion ni
 * log: simplemente la mejora de precision que justifica toda la Fase 4 no
 * existiria, en silencio.
 *
 * Es el mismo patron que el riesgo de la normalizacion de entrada del
 * clasificador, y se cierra igual: ejecutando el modelo real y mirando si los
 * numeros tienen la forma que deberian.
 *
 * La comprobacion de fondo es ornitologica, no numerica: en un punto y una fecha
 * concretos deben ser plausibles unos cientos de especies, no las 6.419 del
 * catalogo mundial.
 */
@RunWith(AndroidJUnit4::class)
class BirdNetContextPriorTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun contextAt(latitude: Double, longitude: Double, month: Int) = ObservationContext(
        latitude = latitude,
        longitude = longitude,
        epochMillis = GregorianCalendar(2026, month - 1, 15).timeInMillis,
        contextInfluence = 1f,
    )

    /** Cuenta cuantas de las etiquetas del modelo reciben multiplicador pleno. */
    private fun countAtFullMultiplier(
        prior: dev.pelayo.aves.core.ml.api.SpeciesPrior,
        labels: List<BirdNetLabel>,
    ): Int = labels.filter { it.isBird }.count { prior[it.provisionalSpeciesId] >= 1.0f }

    private fun loadLabels(): List<BirdNetLabel> =
        context.assets.open(BirdNetAudioClassifier.LABELS_ASSET).bufferedReader().use { reader ->
            context.assets.open(BirdNetAudioClassifier.TAXO_CODES_ASSET).bufferedReader().use { codes ->
                BirdNetLabels.parse(reader.lineSequence(), codes.readText().lineSequence())
            }
        }

    @Test
    fun elPriorDescartaLaInmensaMayoriaDelCatalogoMundial() = runBlocking {
        val labels = loadLabels()

        BirdNetContextPriorProvider(context).use { provider ->
            // Asturias en junio.
            val prior = provider.prior(contextAt(43.3619, -5.8494, month = 6))
            val plausible = countAtFullMultiplier(prior, labels)

            // Si esto fallara por arriba, la salida del modelo no son
            // probabilidades y el prior seria decorativo.
            assertTrue(
                "se esperaban unos cientos de especies plausibles y salieron $plausible " +
                    "de ${labels.count { it.isBird }}. Si es casi todo el catalogo, la salida " +
                    "del meta-modelo probablemente sean logits y no probabilidades.",
                plausible in 20..1500,
            )
        }
    }

    @Test
    fun lugaresDistintosDanConjuntosDeEspeciesDistintos() = runBlocking {
        val labels = loadLabels()

        BirdNetContextPriorProvider(context).use { provider ->
            val asturias = provider.prior(contextAt(43.3619, -5.8494, month = 6))
            val amazonas = provider.prior(contextAt(-3.4653, -62.2159, month = 6))

            val here = labels.filter { it.isBird }
                .filter { asturias[it.provisionalSpeciesId] >= 1.0f }
                .map { it.provisionalSpeciesId }
                .toSet()
            val there = labels.filter { it.isBird }
                .filter { amazonas[it.provisionalSpeciesId] >= 1.0f }
                .map { it.provisionalSpeciesId }
                .toSet()

            assertTrue("ambos deben tener especies", here.isNotEmpty() && there.isNotEmpty())
            // Comparten alguna cosmopolita, pero la mayor parte no puede solapar.
            val overlap = here.intersect(there).size.toDouble() / minOf(here.size, there.size)
            assertTrue(
                "Asturias y el Amazonas comparten el ${(overlap * 100).toInt()}% de especies; " +
                    "eso significa que el prior no depende de la ubicacion",
                overlap < 0.5,
            )
        }
    }

    @Test
    fun elPetirrojoEsPlausibleEnAsturiasYElTucanNo() = runBlocking {
        // Comprobacion ornitologica directa: si esto falla, algo esta mal en el
        // orden del tensor de entrada o en el mapeo de etiquetas.
        BirdNetContextPriorProvider(context).use { provider ->
            val asturias = provider.prior(contextAt(43.3619, -5.8494, month = 6))

            assertEquals(
                "el petirrojo europeo es de lo mas comun que hay en Asturias",
                1.0f,
                asturias["erithacus_rubecula"],
                1e-6f,
            )
            assertTrue(
                "un tucan del Amazonas no deberia ser plausible en Asturias",
                asturias["ramphastos_tucanus"] < 1.0f,
            )
        }
    }

    @Test
    fun ningunPriorValeCero() = runBlocking {
        val labels = loadLabels()

        BirdNetContextPriorProvider(context).use { provider ->
            val prior = provider.prior(contextAt(43.3619, -5.8494, month = 1))

            // Con un cero, un divagante dejaria de ser detectable POR
            // CONSTRUCCION, y es lo mas interesante que puede pasar en esta app.
            val zeros = labels.filter { it.isBird }
                .filter { prior[it.provisionalSpeciesId] <= 0f }
            assertTrue("hay ${zeros.size} especies con prior cero", zeros.isEmpty())
        }
    }

    @Test
    fun sinUbicacionNoFiltraNada() = runBlocking {
        BirdNetContextPriorProvider(context).use { provider ->
            val prior = provider.prior(
                ObservationContext(epochMillis = System.currentTimeMillis(), contextInfluence = 1f),
            )
            // Denegar el permiso de ubicacion degrada la precision, no rompe la
            // deteccion.
            assertEquals(1f, prior["erithacus_rubecula"], 0f)
            assertEquals(1f, prior["ramphastos_tucanus"], 0f)
        }
    }
}
