package dev.pelayo.aves.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.ui.graphics.vector.ImageVector
import dev.pelayo.aves.R

/**
 * Destinos de primer nivel.
 *
 * Identificar va primero y es el destino de arranque: el gesto que importa es
 * "lo oigo ahora mismo, sacalo ya", y cualquier navegacion intermedia cuesta
 * segundos que el pajaro no espera.
 */
enum class AvesDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    IDENTIFY("identify", R.string.destination_identify, Icons.Outlined.GraphicEq),

    // Va entre identificar y la coleccion porque ese es el recorrido real de un
    // avistamiento: se oye, se revisa, y solo entonces entra en la coleccion.
    REVIEW("review", R.string.destination_review, Icons.Filled.RateReview),

    POKEDEX("pokedex", R.string.destination_pokedex, Icons.Filled.GridView),
    SETTINGS("settings", R.string.destination_settings, Icons.Filled.Settings),
}
