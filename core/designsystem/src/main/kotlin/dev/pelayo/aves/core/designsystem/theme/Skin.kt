package dev.pelayo.aves.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Las pieles de la app. Se elige en Ajustes y se guarda por su nombre.
 *
 * Cuatro y no un selector de colores: cada una esta pensada para un momento de
 * uso. [NIGHT] para el amanecer y la escucha nocturna, donde una pantalla clara
 * deslumbra y delata tu posicion a los propios pajaros; [FOREST] para el campo
 * de dia, con los verdes de la marca; [DAWN] para quien quiere una pantalla
 * clara y tranquila sin verde por todas partes; y [SYSTEM], que es como iba la
 * app hasta ahora: los colores del movil y claro u oscuro segun el sistema.
 */
enum class AvesSkin {
    SYSTEM,
    FOREST,
    DAWN,
    NIGHT,
    ;

    companion object {
        fun fromName(name: String?): AvesSkin = entries.firstOrNull { it.name == name } ?: SYSTEM
    }

    /**
     * Si esta piel pinta oscuro. Solo [SYSTEM] depende del sistema; el resto
     * manda aunque el movil diga otra cosa, y los iconos de la barra de estado
     * tienen que seguirla a ella, no al sistema.
     */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        FOREST, DAWN -> false
        NIGHT -> true
    }
}

/** Bosque: los verdes de la marca en claro, con fondos con un punto de hoja. */
internal val ForestColors: ColorScheme = lightColorScheme(
    primary = GreenPrimary,
    onPrimary = GreenOnPrimary,
    primaryContainer = GreenPrimaryContainer,
    onPrimaryContainer = GreenOnPrimaryContainer,
    secondary = OchreSecondary,
    onSecondary = OchreOnSecondary,
    secondaryContainer = OchreSecondaryContainer,
    onSecondaryContainer = OchreOnSecondaryContainer,
    tertiary = SkyTertiary,
    onTertiary = SkyOnTertiary,
    tertiaryContainer = SkyTertiaryContainer,
    onTertiaryContainer = SkyOnTertiaryContainer,
    background = Color(0xFFF3F7EC),
    onBackground = Color(0xFF1A2016),
    surface = Color(0xFFF3F7EC),
    onSurface = Color(0xFF1A2016),
    surfaceVariant = Color(0xFFDCE6D2),
    onSurfaceVariant = Color(0xFF43503B),
    surfaceContainer = Color(0xFFE6EEDD),
    surfaceContainerLow = Color(0xFFEDF3E5),
    surfaceContainerHigh = Color(0xFFDFE9D5),
    surfaceContainerHighest = Color(0xFFD8E3CC),
    outline = Color(0xFF74806A),
    outlineVariant = Color(0xFFC3CEB7),
)

/** Alba: clara y suave, blancos calidos con un azul pizarra de acento. */
internal val DawnColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF3F6B78),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC6E7F2),
    onPrimaryContainer = Color(0xFF00202A),
    secondary = Color(0xFF8A6D3B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE3B8),
    onSecondaryContainer = Color(0xFF2C1A00),
    tertiary = Color(0xFF5E6B4B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE0EBC9),
    onTertiaryContainer = Color(0xFF1B230C),
    background = Color(0xFFFCFAF5),
    onBackground = Color(0xFF1E1C18),
    surface = Color(0xFFFCFAF5),
    onSurface = Color(0xFF1E1C18),
    surfaceVariant = Color(0xFFE9E4D9),
    onSurfaceVariant = Color(0xFF4C4841),
    surfaceContainer = Color(0xFFF1EDE4),
    surfaceContainerLow = Color(0xFFF6F3EC),
    surfaceContainerHigh = Color(0xFFEBE6DC),
    surfaceContainerHighest = Color(0xFFE5E0D5),
    outline = Color(0xFF7D786F),
    outlineVariant = Color(0xFFCEC8BC),
)

/** Noche: la paleta oscura de siempre. */
internal val NightColors: ColorScheme = darkColorScheme(
    primary = GreenPrimaryDark,
    onPrimary = GreenOnPrimaryDark,
    primaryContainer = GreenPrimaryContainerDark,
    onPrimaryContainer = GreenOnPrimaryContainerDark,
    secondary = OchreSecondaryDark,
    onSecondary = OchreOnSecondaryDark,
    secondaryContainer = OchreSecondaryContainerDark,
    onSecondaryContainer = OchreOnSecondaryContainerDark,
    tertiary = SkyTertiaryDark,
    onTertiary = SkyOnTertiaryDark,
    tertiaryContainer = SkyTertiaryContainerDark,
    onTertiaryContainer = SkyOnTertiaryContainerDark,
)
