package dev.pelayo.aves.licensing

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La cara opuesta de [OpenFlavorLicenseTest]: comprueba que el flavor
 * `personal` SI trae BirdNET.
 *
 * Sin este test, el de `open` se cumpliria trivialmente si alguien rompiera el
 * enlace del modelo en los dos flavors: la app dejaria de identificar nada y la
 * comprobacion de licencias seguiria en verde, dando una falsa sensacion de que
 * todo esta bien.
 */
class PersonalFlavorLicenseTest {

    @Test
    fun `el flavor personal si enlaza BirdNET`() {
        val present = runCatching {
            Class.forName("dev.pelayo.aves.core.ml.birdnet.BirdNetAudioClassifier")
        }.isSuccess

        assertTrue(
            "El flavor `personal` deberia incluir BirdNET. Si no esta, la app no " +
                "identifica nada y el test de licencias del flavor `open` pasaria " +
                "por la razon equivocada.",
            present,
        )
    }
}
