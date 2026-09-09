package dev.pelayo.aves.core.ml.birdnetplus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pelayo.aves.core.ml.api.ObservationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.util.GregorianCalendar

/**
 * Ejecuta el geomodelo de BirdNET+ de verdad.
 *
 * Lo que se comprueba no es un numero exacto sino el sentido comun: un
 * petirrojo en Madrid en abril tiene que salir muy por encima de un pinguino
 * emperador. Un desajuste grosero en la convencion de entrada (orden de las
 * coordenadas, escala de la semana) lo tumbaria; un desplazamiento fino en el
 * tiempo no, y eso es lo que este test NO puede ver.
 */
@RunWith(AndroidJUnit4::class)
class BirdNetPlusGeoPriorTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val madrid = 40.4168 to -3.7038
    private val april = GregorianCalendar(2026, 3, 15).timeInMillis

    @Test
    fun cargaYSuFicheroDeEtiquetasCuadraConElModelo() = runBlocking {
        BirdNetPlusGeoPriorProvider(context, ZoneId.of("Europe/Madrid")).use { provider ->
            provider.warmUp()
            // Si no cuadraran, `run` lanzaria al comparar tamanos.
            provider.prior(ObservationContext(madrid.first, madrid.second, april, 1f))
        }
        Unit
    }

    @Test
    fun unPetirrojoEnMadridPesaMasQueUnPinguino() = runBlocking {
        BirdNetPlusGeoPriorProvider(context, ZoneId.of("Europe/Madrid")).use { provider ->
            val prior = provider.prior(ObservationContext(madrid.first, madrid.second, april, 1f))

            val robin = prior["erithacus_rubecula"]
            val penguin = prior["aptenodytes_forsteri"]

            assertEquals("el petirrojo es de lo mas comun en Madrid", 1f, robin, 1e-6f)
            assertEquals("el pinguino emperador no", BirdNetPlusGeoPriorProvider.FLOOR_MULTIPLIER, penguin, 1e-6f)
        }
    }

    @Test
    fun ningunMultiplicadorEsCero() = runBlocking {
        BirdNetPlusGeoPriorProvider(context, ZoneId.of("Europe/Madrid")).use { provider ->
            val prior = provider.prior(ObservationContext(madrid.first, madrid.second, april, 1f))
            assertTrue(prior["aptenodytes_forsteri"] > 0f)
            assertTrue(prior["especie_que_no_existe"] > 0f)
        }
    }

    @Test
    fun sinUbicacionEsNeutro() = runBlocking {
        BirdNetPlusGeoPriorProvider(context).use { provider ->
            val prior = provider.prior(ObservationContext(epochMillis = april, contextInfluence = 1f))
            assertEquals(1f, prior["erithacus_rubecula"], 0f)
        }
    }
}
