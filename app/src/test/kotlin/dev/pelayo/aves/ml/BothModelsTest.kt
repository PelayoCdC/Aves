package dev.pelayo.aves.ml

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La app es una sola y lleva los dos modelos de cantos: el ajuste "Modelo de
 * cantos" deja elegir entre ellos o usar los dos. Si alguien quita uno del
 * build, la opcion seguiria en la pantalla y fallaria al escuchar.
 */
class BothModelsTest {

    @Test
    fun `lleva BirdNET v2_4`() = assertPresent("dev.pelayo.aves.core.ml.birdnet.BirdNetAudioClassifier")

    @Test
    fun `lleva BirdNET plus`() = assertPresent("dev.pelayo.aves.core.ml.birdnetplus.BirdNetPlusAudioClassifier")

    private fun assertPresent(className: String) {
        assertTrue("Falta $className en el build", runCatching { Class.forName(className) }.isSuccess)
    }
}
