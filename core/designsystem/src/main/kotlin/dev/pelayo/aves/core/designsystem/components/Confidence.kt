package dev.pelayo.aves.core.designsystem.components

import androidx.compose.runtime.compositionLocalOf
import kotlin.math.roundToInt

/**
 * La confianza del modelo como la lee una persona: "87 %", sin decimales y con
 * espacio fino inseparable antes del signo, como se escribe en castellano.
 *
 * Solo se ensena si el usuario lo pide (ajuste "Mostrar confianza"): por
 * defecto la app ensena nombres, no numeros.
 */
fun formatConfidence(score: Float): String {
    val percent = if (score.isNaN()) 0 else (score.coerceIn(0f, 1f) * 100f).roundToInt()
    return "$percent$NARROW_NO_BREAK_SPACE%"
}

private val NARROW_NO_BREAK_SPACE = Char(0x202F)

/**
 * El ajuste "Mostrar confianza", disponible en toda la interfaz. Lo pone la
 * actividad a partir de los ajustes; cada fila que ensena una especie
 * detectada lo lee en vez de recibirlo por cinco capas de parametros.
 */
val LocalShowConfidence = compositionLocalOf { false }
