package dev.pelayo.aves.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
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
)

/**
 * El tema segun la piel elegida en Ajustes. Es la entrada que usa la app; la
 * otra sobrecarga queda para las previews.
 */
@Composable
fun AvesTheme(
    skin: AvesSkin,
    content: @Composable () -> Unit,
) {
    when (skin) {
        AvesSkin.SYSTEM -> AvesTheme(content = content)
        AvesSkin.FOREST -> MaterialTheme(colorScheme = ForestColors, typography = AvesTypography, content = content)
        AvesSkin.DAWN -> MaterialTheme(colorScheme = DawnColors, typography = AvesTypography, content = content)
        AvesSkin.NIGHT -> MaterialTheme(colorScheme = NightColors, typography = AvesTypography, content = content)
    }
}

/** Como iba la app hasta las pieles: colores del movil y claro u oscuro segun el sistema. */
@Composable
fun AvesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Color dinamico de Android 12+. Se puede desactivar para que la marca mande. */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> NightColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AvesTypography,
        content = content,
    )
}
