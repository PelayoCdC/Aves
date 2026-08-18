package dev.pelayo.aves.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.pelayo.aves.core.designsystem.theme.AvesTheme

/**
 * Destino vacio de Fase 0. Acabara alojando el slider de influencia del
 * contexto y la pantalla de atribucion de modelos, que no es opcional: la
 * licencia CC BY-NC-SA de BirdNET la exige.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Ajustes",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Influencia del contexto, modelos y atribucion.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    AvesTheme { SettingsScreen() }
}
