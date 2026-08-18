package dev.pelayo.aves.core.designsystem.theme

import androidx.compose.ui.graphics.Color

// Paleta base: verdes de vegetacion con un acento ocre de plumaje.
// El tema oscuro no es decorativo aqui: el uso real de la app es al amanecer o
// de noche (escucha nocturna), donde una pantalla clara deslumbra y delata tu
// posicion a los propios pajaros.

internal val GreenPrimary = Color(0xFF4C6B3C)
internal val GreenOnPrimary = Color(0xFFFFFFFF)
internal val GreenPrimaryContainer = Color(0xFFCDEDB6)
internal val GreenOnPrimaryContainer = Color(0xFF0B2000)

internal val OchreSecondary = Color(0xFF8A6D3B)
internal val OchreOnSecondary = Color(0xFFFFFFFF)
internal val OchreSecondaryContainer = Color(0xFFFFDDAE)
internal val OchreOnSecondaryContainer = Color(0xFF2C1A00)

internal val SkyTertiary = Color(0xFF3A6470)
internal val SkyOnTertiary = Color(0xFFFFFFFF)
internal val SkyTertiaryContainer = Color(0xFFBDEAF8)
internal val SkyOnTertiaryContainer = Color(0xFF001F27)

internal val GreenPrimaryDark = Color(0xFFB1D19B)
internal val GreenOnPrimaryDark = Color(0xFF1F3711)
internal val GreenPrimaryContainerDark = Color(0xFF354E26)
internal val GreenOnPrimaryContainerDark = Color(0xFFCDEDB6)

internal val OchreSecondaryDark = Color(0xFFFDBA4B)
internal val OchreOnSecondaryDark = Color(0xFF492F00)
internal val OchreSecondaryContainerDark = Color(0xFF6A4E25)
internal val OchreOnSecondaryContainerDark = Color(0xFFFFDDAE)

internal val SkyTertiaryDark = Color(0xFFA2CEDC)
internal val SkyOnTertiaryDark = Color(0xFF023641)
internal val SkyTertiaryContainerDark = Color(0xFF204C58)
internal val SkyOnTertiaryContainerDark = Color(0xFFBDEAF8)

/**
 * Colores por tier de rareza. Se definen en el design system y no en la feature
 * porque los usan tanto la Pokedex como la pantalla de identificacion, y tienen
 * que coincidir: si un ave sale dorada al identificarla, debe salir dorada en la
 * coleccion.
 */
val RarityColors = listOf(
    Color(0xFF8E8E8E), // 0 muy comun
    Color(0xFF4C8C4A), // 1 comun
    Color(0xFF3A6EA5), // 2 poco comun
    Color(0xFF8E5BC4), // 3 rara
    Color(0xFFD4A017), // 4 excepcional
)
