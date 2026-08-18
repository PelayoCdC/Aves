package dev.pelayo.aves.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Tipografia M3 por defecto, con una excepcion: los nombres cientificos van en
 * cursiva por convencion taxonomica. No es un capricho de diseno, es como se
 * escriben.
 */
val AvesTypography = Typography()

val ScientificNameStyle = TextStyle(
    fontFamily = FontFamily.Serif,
    fontStyle = FontStyle.Italic,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
)
