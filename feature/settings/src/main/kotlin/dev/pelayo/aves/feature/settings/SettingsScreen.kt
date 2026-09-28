package dev.pelayo.aves.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pelayo.aves.core.designsystem.theme.AvesSkin
import dev.pelayo.aves.core.designsystem.theme.AvesTheme
import dev.pelayo.aves.core.ml.api.AudioModelChoice
import dev.pelayo.aves.core.ml.api.ModelDescriptor
import kotlin.math.roundToInt

/**
 * Ajustes de la app.
 *
 * Todo lo que hay aqui LLEGA a alguna parte: la escucha lee estos valores al
 * arrancar y la camara al disparar. La primera version guardaba los cuatro
 * controles y nadie los leia.
 *
 * La seccion de atribucion no es decorativa: los modelos de BirdNET son
 * CC BY-NC-SA 4.0 y citarlos es un requisito de la licencia. Por eso se dibuja
 * siempre que haya modelos, sin plegar ni ocultar, y por eso [modelDescriptors]
 * llega desde fuera: quien sabe que modelos hay enlazados es el flavor
 * compilado, no esta pantalla.
 *
 * @param viewModelFactory lo construye quien tiene el almacen y los
 *   repositorios. Mismo patron que `ReviewScreen`.
 * @param onExportData null cuando la compilacion no ofrece exportacion.
 */
@Composable
fun SettingsScreen(
    viewModelFactory: () -> SettingsViewModel,
    modelDescriptors: List<ModelDescriptor> = emptyList(),
    onExportData: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** Guardar la coleccion entera en un .zip. Null esconde el boton. */
    onBackupExport: (() -> Unit)? = null,
    /** Elegir un .zip y sustituir la coleccion por el. Null esconde el boton. */
    onBackupRestore: (() -> Unit)? = null,
) {
    val viewModel: SettingsViewModel = viewModel { viewModelFactory() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsContent(
        state = state,
        modelDescriptors = modelDescriptors,
        onContextInfluenceChange = viewModel::setContextInfluence,
        onConfidenceThresholdChange = viewModel::setConfidenceThreshold,
        onLocationDisabledChange = viewModel::setLocationDisabled,
        onListeningCutoffChange = viewModel::setListeningCutoff,
        onSkinChange = viewModel::setSkin,
        onShowConfidenceChange = viewModel::setShowConfidence,
        onAudioModelChange = viewModel::setAudioModel,
        onExportData = onExportData,
        onBackupExport = onBackupExport,
        onBackupRestore = onBackupRestore,
        modifier = modifier,
    )
}

/**
 * La pantalla sin ViewModel, para poder previsualizarla con datos a mano.
 */
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    modelDescriptors: List<ModelDescriptor>,
    onContextInfluenceChange: (Float) -> Unit,
    onConfidenceThresholdChange: (Float) -> Unit,
    onLocationDisabledChange: (Boolean) -> Unit,
    onListeningCutoffChange: (Int) -> Unit,
    onExportData: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onSkinChange: (AvesSkin) -> Unit = {},
    onShowConfidenceChange: (Boolean) -> Unit = {},
    onBackupExport: (() -> Unit)? = null,
    onBackupRestore: (() -> Unit)? = null,
    onAudioModelChange: (AudioModelChoice) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)

        // Los controles esperan a la primera lectura del disco. Dibujarlos ya con
        // los valores por defecto haria que el usuario viese saltar sus propios
        // ajustes un instante despues de abrir la pantalla.
        if (!state.isLoading) {
            AppearanceSection(
                selected = AvesSkin.fromName(state.settings.skin),
                onSkinChange = onSkinChange,
            )

            IdentificationSection(
                settings = state.settings,
                onContextInfluenceChange = onContextInfluenceChange,
                onConfidenceThresholdChange = onConfidenceThresholdChange,
                onShowConfidenceChange = onShowConfidenceChange,
                onAudioModelChange = onAudioModelChange,
            )

            PrivacySection(
                settings = state.settings,
                onLocationDisabledChange = onLocationDisabledChange,
                onListeningCutoffChange = onListeningCutoffChange,
            )
        }

        // La atribucion NO espera a nada ni depende de ningun ajuste: es
        // requisito de licencia y se dibuja siempre.
        AttributionSection(modelDescriptors = modelDescriptors)

        DataSection(
            collectedSpecies = state.collectedSpecies,
            collectionBytes = state.collectionBytes,
            onExportData = onExportData,
            onBackupExport = onBackupExport,
            onBackupRestore = onBackupRestore,
        )
    }
}

// --- Aspecto ---------------------------------------------------------------

/**
 * Las pieles, como una fila de chips. Cuatro opciones caben en una pantalla y
 * se comparan de un vistazo; un desplegable las esconderia.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection(
    selected: AvesSkin,
    onSkinChange: (AvesSkin) -> Unit,
) {
    SettingsSection(
        title = stringResource(R.string.settings_appearance_title),
        subtitle = stringResource(R.string.settings_appearance_subtitle),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (skin in AvesSkin.entries) {
                FilterChip(
                    selected = skin == selected,
                    onClick = { onSkinChange(skin) },
                    label = { Text(stringResource(skinName(skin))) },
                    leadingIcon = if (skin == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(skinDescription(selected)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Que modelo escucha: BirdNET v2.4, BirdNET+ o los dos. Se aplica en la
 * siguiente escucha o el siguiente fichero; la que este en marcha sigue con el
 * suyo.
 */
@Composable
private fun AudioModelSetting(
    selected: AudioModelChoice,
    onChange: (AudioModelChoice) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.settings_audio_model), style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (model in AudioModelChoice.entries) {
                FilterChip(
                    selected = model == selected,
                    onClick = { onChange(model) },
                    label = { Text(stringResource(audioModelName(model))) },
                    leadingIcon = if (model == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                )
            }
        }
        Text(
            text = stringResource(audioModelDescription(selected)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun audioModelName(model: AudioModelChoice): Int = when (model) {
    AudioModelChoice.BIRDNET -> R.string.settings_audio_model_birdnet
    AudioModelChoice.BIRDNET_PLUS -> R.string.settings_audio_model_birdnet_plus
    AudioModelChoice.BOTH -> R.string.settings_audio_model_both
}

private fun audioModelDescription(model: AudioModelChoice): Int = when (model) {
    AudioModelChoice.BIRDNET -> R.string.settings_audio_model_birdnet_help
    AudioModelChoice.BIRDNET_PLUS -> R.string.settings_audio_model_birdnet_plus_help
    AudioModelChoice.BOTH -> R.string.settings_audio_model_both_help
}

private fun skinName(skin: AvesSkin): Int = when (skin) {
    AvesSkin.SYSTEM -> R.string.settings_skin_system
    AvesSkin.FOREST -> R.string.settings_skin_forest
    AvesSkin.DAWN -> R.string.settings_skin_dawn
    AvesSkin.NIGHT -> R.string.settings_skin_night
}

private fun skinDescription(skin: AvesSkin): Int = when (skin) {
    AvesSkin.SYSTEM -> R.string.settings_skin_system_desc
    AvesSkin.FOREST -> R.string.settings_skin_forest_desc
    AvesSkin.DAWN -> R.string.settings_skin_dawn_desc
    AvesSkin.NIGHT -> R.string.settings_skin_night_desc
}

// --- Identificacion --------------------------------------------------------

@Composable
private fun IdentificationSection(
    settings: AvesSettings,
    onContextInfluenceChange: (Float) -> Unit,
    onConfidenceThresholdChange: (Float) -> Unit,
    onShowConfidenceChange: (Boolean) -> Unit,
    onAudioModelChange: (AudioModelChoice) -> Unit,
) {
    SettingsSection(
        title = stringResource(R.string.settings_identification_title),
        subtitle = stringResource(R.string.settings_identification_subtitle),
    ) {
        AudioModelSetting(
            selected = AudioModelChoice.fromName(settings.audioModel),
            onChange = onAudioModelChange,
        )

        Spacer(Modifier.size(16.dp))

        SliderSetting(
            label = stringResource(R.string.settings_context_influence),
            value = settings.contextInfluence,
            valueRange = AvesSettings.CONTEXT_INFLUENCE_RANGE,
            steps = CONTEXT_INFLUENCE_STEPS,
            explanation = ::contextInfluenceLine,
            onCommit = onContextInfluenceChange,
        )

        Spacer(Modifier.size(16.dp))

        SliderSetting(
            label = stringResource(R.string.settings_confidence_threshold),
            value = settings.confidenceThreshold,
            valueRange = AvesSettings.CONFIDENCE_THRESHOLD_RANGE,
            steps = CONFIDENCE_THRESHOLD_STEPS,
            explanation = ::confidenceThresholdLine,
            onCommit = onConfidenceThresholdChange,
        )

        Spacer(Modifier.size(16.dp))

        SwitchSetting(
            label = stringResource(R.string.settings_show_confidence),
            checked = settings.showConfidence,
            onCheckedChange = onShowConfidenceChange,
            explanation = stringResource(
                if (settings.showConfidence) R.string.settings_show_confidence_on else R.string.settings_show_confidence_off,
            ),
        )
    }
}

// --- Privacidad ------------------------------------------------------------

@Composable
private fun PrivacySection(
    settings: AvesSettings,
    onLocationDisabledChange: (Boolean) -> Unit,
    onListeningCutoffChange: (Int) -> Unit,
) {
    SettingsSection(
        title = stringResource(R.string.settings_privacy_title),
        subtitle = stringResource(R.string.settings_privacy_subtitle),
    ) {
        SwitchSetting(
            label = stringResource(R.string.settings_location_disabled),
            checked = settings.locationDisabled,
            onCheckedChange = onLocationDisabledChange,
            explanation = stringResource(
                if (settings.locationDisabled) {
                    R.string.settings_location_disabled_on
                } else {
                    R.string.settings_location_disabled_off
                },
            ),
        )

        Spacer(Modifier.size(16.dp))

        CutoffSetting(
            minutes = settings.listeningCutoffMinutes,
            onCommit = onListeningCutoffChange,
        )

        Spacer(Modifier.size(16.dp))
        HorizontalDivider()
        Spacer(Modifier.size(16.dp))

        // Enumerado en concreto y no como una politica generica: cualquiera puede
        // contrastar cada linea con el codigo. Una app que graba el microfono en
        // continuo y usa ubicacion se merece esta explicacion, y ademas es lo
        // primero que mira Play Store.
        Text(
            text = stringResource(R.string.settings_data_collected_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.size(8.dp))
        PrivacyFact(stringResource(R.string.settings_fact_microphone))
        PrivacyFact(stringResource(R.string.settings_fact_clips))
        PrivacyFact(stringResource(R.string.settings_fact_location))
        PrivacyFact(stringResource(R.string.settings_fact_network))
        PrivacyFact(stringResource(R.string.settings_fact_local))
    }
}

@Composable
private fun PrivacyFact(text: String) {
    Row(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Atribucion ------------------------------------------------------------

/**
 * Atribucion de los modelos.
 *
 * No lleva interruptor ni plegado: CC BY-NC-SA exige la atribucion, asi que
 * poder esconderla equivaldria a poder incumplir la licencia desde los ajustes.
 */
@Composable
private fun AttributionSection(modelDescriptors: List<ModelDescriptor>) {
    SettingsSection(
        title = stringResource(R.string.settings_attribution_title),
        subtitle = stringResource(R.string.settings_attribution_subtitle),
    ) {
        if (modelDescriptors.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_attribution_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SettingsSection
        }

        for (descriptor in modelDescriptors) {
            ModelAttributionCard(descriptor)
            Spacer(Modifier.size(12.dp))
        }
    }
}

@Composable
private fun ModelAttributionCard(descriptor: ModelDescriptor) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = descriptor.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (!descriptor.allowsCommercialUse) {
                    NonCommercialBadge()
                }
            }

            Spacer(Modifier.size(4.dp))
            Text(
                text = stringResource(
                    R.string.settings_attribution_license_species,
                    descriptor.licenseSpdx,
                    speciesCountLabel(descriptor.speciesCount),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.size(8.dp))
            Text(
                text = descriptor.attributionText,
                style = MaterialTheme.typography.bodySmall,
            )

            if (!descriptor.allowsCommercialUse) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.settings_non_commercial_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Distintivo del modelo no comercial. Se ve de un vistazo, que es el objetivo. */
@Composable
private fun NonCommercialBadge() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = stringResource(R.string.settings_non_commercial_badge),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

// --- Datos -----------------------------------------------------------------

@Composable
private fun DataSection(
    collectedSpecies: Int?,
    collectionBytes: Long?,
    onExportData: (() -> Unit)?,
    onBackupExport: (() -> Unit)? = null,
    onBackupRestore: (() -> Unit)? = null,
) {
    SettingsSection(
        title = stringResource(R.string.settings_data_title),
        subtitle = stringResource(R.string.settings_data_subtitle),
    ) {
        collectionSizeLine(collectedSpecies, collectionBytes)?.let { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(12.dp))
        }

        if (onExportData != null) {
            Button(onClick = onExportData) {
                Text(stringResource(R.string.settings_export))
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.settings_export_help),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // La copia entera: lo que hace falta para cambiar de movil sin perder
        // nada, con o sin la copia automatica de Google.
        if (onBackupExport != null || onBackupRestore != null) {
            Spacer(Modifier.size(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onBackupExport?.let { export ->
                    Button(onClick = export) { Text(stringResource(R.string.settings_backup_export)) }
                }
                onBackupRestore?.let { restore ->
                    OutlinedButton(onClick = restore) { Text(stringResource(R.string.settings_backup_restore)) }
                }
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.settings_backup_help),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- Piezas reutilizables --------------------------------------------------

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        content()
    }
}

/**
 * Deslizador con su frase explicativa.
 *
 * El valor en curso vive en un estado local y solo se persiste al soltar: con
 * DataStore detras, escribir en cada fotograma del arrastre serian decenas de
 * escrituras en disco por gesto. La frase, en cambio, sigue al dedo, que es lo
 * que hace que se entienda que significa el numero.
 */
@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    explanation: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var draft by remember(value) { mutableFloatStateOf(value) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.titleSmall)
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onCommit(draft) },
            valueRange = valueRange,
            steps = steps,
        )
        Text(
            text = explanation(draft),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CutoffSetting(minutes: Int, onCommit: (Int) -> Unit) {
    var draft by remember(minutes) { mutableFloatStateOf(minutes.toFloat()) }
    val snapped = AvesSettings.sanitizeListeningCutoff(draft.roundToInt())

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_listening_cutoff),
            style = MaterialTheme.typography.titleSmall,
        )
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onCommit(snapped) },
            valueRange = AvesSettings.LISTENING_CUTOFF_RANGE.first.toFloat()..
                AvesSettings.LISTENING_CUTOFF_RANGE.last.toFloat(),
            steps = LISTENING_CUTOFF_STEPS,
        )
        Text(
            text = listeningCutoffLine(snapped),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    explanation: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        Text(
            text = explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Posiciones intermedias de cada deslizador, que Compose cuenta SIN incluir los
 * dos extremos: valores discretos - 2.
 *
 * Los tres van en escalones y no en continuo porque el usuario no necesita
 * distinguir un 62 % de un 63 %, y en cambio si necesita poder volver al mismo
 * sitio despues de haber probado otra cosa.
 */
// 0,00 a 1,00 de 0,05 en 0,05: 21 valores.
private const val CONTEXT_INFLUENCE_STEPS = 19

// 0,05 a 0,95 de 0,05 en 0,05: 19 valores.
private const val CONFIDENCE_THRESHOLD_STEPS = 17

// 0 a 120 minutos de 5 en 5: 25 valores.
private const val LISTENING_CUTOFF_STEPS = 23

// --- Previsualizaciones ----------------------------------------------------

private val SampleDescriptors = listOf(
    ModelDescriptor(
        modelId = "birdnet-v2.4",
        displayName = "BirdNET v2.4",
        licenseSpdx = "CC-BY-NC-SA-4.0",
        allowsCommercialUse = false,
        attributionText = "Identificación por canto mediante BirdNET (Stefan Kahl, " +
            "Connor Wood, Maximilian Eibl, Holger Klinck), K. Lisa Yang Center for " +
            "Conservation Bioacoustics, Cornell Lab of Ornithology. Modelo bajo " +
            "CC BY-NC-SA 4.0.",
        speciesCount = 6419,
    ),
    ModelDescriptor(
        modelId = "aiy-birds-v1-v3",
        displayName = "Google AIY birds V1",
        licenseSpdx = "Apache-2.0",
        allowsCommercialUse = true,
        attributionText = "Clasificador de imagen google/aiy/vision/classifier/birds_V1 " +
            "(variación 3), Google, bajo Apache 2.0.",
        speciesCount = 964,
    ),
)

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun SettingsScreenPreview() {
    AvesTheme(dynamicColor = false) {
        SettingsContent(
            state = SettingsUiState(
                isLoading = false,
                settings = AvesSettings(),
                collectedSpecies = 42,
                collectionBytes = 12_400_000L,
            ),
            modelDescriptors = SampleDescriptors,
            onContextInfluenceChange = {},
            onConfidenceThresholdChange = {},
            onLocationDisabledChange = {},
            onListeningCutoffChange = {},
            onExportData = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 1400, name = "Sin ubicación y sin modelos")
@Composable
private fun SettingsScreenNoLocationPreview() {
    AvesTheme(dynamicColor = false) {
        SettingsContent(
            state = SettingsUiState(
                isLoading = false,
                settings = AvesSettings(
                    contextInfluence = 0f,
                    locationDisabled = true,
                    confidenceThreshold = 0.8f,
                    listeningCutoffMinutes = AvesSettings.NO_CUTOFF,
                ),
                collectedSpecies = 0,
                collectionBytes = null,
            ),
            modelDescriptors = emptyList(),
            onContextInfluenceChange = {},
            onConfidenceThresholdChange = {},
            onLocationDisabledChange = {},
            onListeningCutoffChange = {},
            onExportData = null,
        )
    }
}
