package dev.pelayo.aves.licensing

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * La defensa automatica contra publicar por accidente un modelo no comercial.
 *
 * El flavor `open` existe para poder distribuir la app algun dia. Los modelos de
 * BirdNET son CC BY-NC-SA 4.0 y no pueden ir dentro. La proteccion no puede ser
 * "acordarse": basta que alguien escriba `implementation` en vez de
 * `personalImplementation` en una linea del build para que un modelo no
 * comercial acabe en un APK publicable, y nada lo avisaria.
 *
 * Este test solo se compila y ejecuta en el flavor `open` (vive en el conjunto
 * de fuentes `testOpen`), y comprueba por reflexion que las clases de los
 * modelos restringidos NO estan en el classpath. No basta con no usarlas: no
 * deben estar.
 */
class OpenFlavorLicenseTest {

    @Test
    fun `el flavor open no enlaza ningun modelo no comercial`() {
        for (className in NON_COMMERCIAL_CLASSES) {
            val present = runCatching { Class.forName(className) }.isSuccess
            assertFalse(
                "El flavor `open` es el publicable y no puede contener $className, " +
                    "cuyo modelo es CC BY-NC-SA 4.0. Revisa app/build.gradle.kts: " +
                    "debe estar como `personalImplementation`, nunca como `implementation`.",
                present,
            )
        }
    }

    private companion object {
        /** Clases cuya sola presencia en el APK seria un problema de licencia. */
        val NON_COMMERCIAL_CLASSES = listOf(
            "dev.pelayo.aves.core.ml.birdnet.BirdNetAudioClassifier",
        )
    }
}
