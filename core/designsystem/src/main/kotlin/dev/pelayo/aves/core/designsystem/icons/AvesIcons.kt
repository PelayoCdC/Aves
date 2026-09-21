package dev.pelayo.aves.core.designsystem.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Los iconos propios de la app, y los de Material que tienen un significado fijo
 * en ella.
 *
 * Estan aqui y no repartidos por las pantallas porque "como se ha conseguido
 * esta especie" se pinta en la rejilla, en la ficha y en cada avistamiento, y los
 * tres sitios tienen que usar exactamente el mismo dibujo.
 */
object AvesIcons {

    /** La has visto: un avistamiento anotado a mano. */
    val Seen: ImageVector get() = Icons.Filled.Visibility

    /** La has fotografiado: identificada por foto, de la camara o de la galeria. */
    val Photographed: ImageVector get() = Icons.Filled.PhotoCamera

    /**
     * La has oido: detectada por su canto.
     *
     * Una clave de sol y no la nota suelta de Material: la nota ya significa
     * "reproducir audio" en media movil, y aqui lo que se cuenta es otra cosa.
     * Material no trae clave de sol; el trazado es el `music-clef-treble` de
     * Material Design Icons (Pictogrammers, Apache 2.0), sobre la misma rejilla
     * de 24 que el resto, asi que se alinea con los otros dos sin ajustes.
     */
    val Heard: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "TrebleClef",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(TREBLE_CLEF_PATH),
            fill = SolidColor(Color.Black),
        ).build()
    }

    private const val TREBLE_CLEF_PATH =
        "M13,11V7.5L15.2,5.29C16,4.5 16.15,3.24 15.59,2.26C15.14,1.47 14.32,1 13.45,1" +
            "C13.24,1 13,1.03 12.81,1.09C11.73,1.38 11,2.38 11,3.5V6.74L7.86,9.91" +
            "C6.2,11.6 5.7,14.13 6.61,16.34C7.38,18.24 9.06,19.55 11,19.89V20.5" +
            "C11,20.76 10.77,21 10.5,21H9V23H10.5A2.5,2.5 0 0,0 13,20.5V20" +
            "C15.03,20 17.16,18.08 17.16,15.25C17.16,12.95 15.24,11 13,11" +
            "M13,3.5C13,3.27 13.11,3.09 13.32,3.03C13.54,2.97 13.77,3.06 13.88,3.26" +
            "C14,3.46 13.96,3.71 13.8,3.87L13,4.73V3.5" +
            "M11,11.5C10.03,12.14 9.3,13.24 9.04,14.26L11,14.78V17.83" +
            "C9.87,17.53 8.9,16.71 8.43,15.57C7.84,14.11 8.16,12.45 9.26,11.33L11,9.5V11.5" +
            "M13,18V12.94C14.17,12.94 15.18,14.04 15.18,15.25C15.18,17 13.91,18 13,18Z"
}
