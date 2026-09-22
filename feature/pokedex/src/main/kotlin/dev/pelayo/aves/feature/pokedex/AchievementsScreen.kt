package dev.pelayo.aves.feature.pokedex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pelayo.aves.core.designsystem.theme.AvesTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Las medallas.
 *
 * Una rejilla de medallas, y no una lista con barras, porque la gracia es
 * verlas todas de golpe: cuantas de oro, cuantas grises. La descripcion y lo que
 * falta van dentro de cada una, al tocarla.
 */
@Composable
fun AchievementsScreen(
    viewModelFactory: () -> AchievementsViewModel,
    modifier: Modifier = Modifier,
) {
    val viewModel: AchievementsViewModel = viewModel { viewModelFactory() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    AchievementsContent(
        state = state,
        onSelect = viewModel::select,
        onRetry = viewModel::downloadMissing,
        modifier = modifier,
    )
}

@Composable
internal fun AchievementsContent(
    state: AchievementsUiState,
    onSelect: (Achievement?) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "cabecera", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(text = stringResource(R.string.pokedex_achievements), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.size(4.dp))
                Text(
                    text = stringResource(R.string.pokedex_achievements_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    state.isDownloading -> Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.pokedex_achievements_downloading),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    state.downloadFailed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.pokedex_achievements_offline),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.pokedex_achievements_retry)) }
                    }
                }
            }
        }

        items(state.medals, key = { it.id }) { medal ->
            MedalCell(medal = medal, onClick = { onSelect(medal) })
        }
    }

    state.selected?.let { medal ->
        MedalDialog(medal = medal, onDismiss = { onSelect(null) })
    }
}

@Composable
private fun MedalCell(medal: Achievement, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MedalArt(medal = medal, size = 72.dp)
            Spacer(Modifier.size(8.dp))
            Text(
                text = medalTitle(medal),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (medal.hasData) tierName(medal.tier) else stringResource(R.string.pokedex_tier_none),
                style = MaterialTheme.typography.labelSmall,
                color = if (medal.tier.isEarned) tierColor(medal.tier) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** La descripcion de una medalla y cuanto falta para la siguiente. */
@Composable
private fun MedalDialog(medal: Achievement, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(medalTitle(medal)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                MedalArt(medal = medal, size = 140.dp)
                Spacer(Modifier.size(12.dp))
                Text(
                    text = "${medalKind(medal)} · ${if (medal.hasData) tierName(medal.tier) else stringResource(R.string.pokedex_tier_none)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (medal.tier.isEarned) tierColor(medal.tier) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = when (medal.kind) {
                        AchievementKind.COLLECTOR -> stringResource(R.string.pokedex_medal_collector_desc)
                        AchievementKind.ZONE, AchievementKind.PLACE -> stringResource(R.string.pokedex_medal_zone_desc, medal.title)
                        AchievementKind.FAMILY -> stringResource(R.string.pokedex_medal_family_desc, medal.title)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(16.dp))
                val total = medal.total
                if (medal.hasData && total != null) {
                    LinearProgressIndicator(progress = { medal.fraction }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = stringResource(
                            R.string.pokedex_medal_progress,
                            formatThousands(medal.collected),
                            formatThousands(total),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val next = medal.tier.next
                    val remaining = medal.remainingForNext
                    Text(
                        text = if (next != null && remaining != null) {
                            stringResource(R.string.pokedex_medal_next, remaining, tierName(next))
                        } else {
                            stringResource(R.string.pokedex_medal_max)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = stringResource(
                            R.string.pokedex_medal_thresholds,
                            percent(medal.scale, MedalTier.IRON),
                            percent(medal.scale, MedalTier.BRONZE),
                            percent(medal.scale, MedalTier.SILVER),
                            percent(medal.scale, MedalTier.GOLD),
                            percent(medal.scale, MedalTier.PLATINUM),
                            percent(medal.scale, MedalTier.DIAMOND),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.pokedex_medal_no_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pokedex_medal_close)) } },
    )
}

/** "0,5" o "15": el umbral de un metal, sin decimales que sobren. */
private fun percent(scale: MedalScale, tier: MedalTier): String {
    val value = (scale.thresholdOf(tier) ?: 0f) * 100f
    val tenths = (value * 10).roundToInt()
    return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10},${tenths % 10}"
}

@Composable
private fun medalTitle(medal: Achievement): String =
    if (medal.kind == AchievementKind.COLLECTOR) stringResource(R.string.pokedex_medal_collector) else medal.title

@Composable
private fun medalKind(medal: Achievement): String = stringResource(
    when (medal.kind) {
        AchievementKind.COLLECTOR -> R.string.pokedex_medal_kind_collector
        AchievementKind.ZONE -> R.string.pokedex_medal_kind_zone
        AchievementKind.PLACE -> R.string.pokedex_medal_kind_place
        AchievementKind.FAMILY -> R.string.pokedex_medal_kind_family
    },
)

@Composable
internal fun tierName(tier: MedalTier): String = stringResource(
    when (tier) {
        MedalTier.NONE -> R.string.pokedex_tier_none
        MedalTier.IRON -> R.string.pokedex_tier_iron
        MedalTier.BRONZE -> R.string.pokedex_tier_bronze
        MedalTier.SILVER -> R.string.pokedex_tier_silver
        MedalTier.GOLD -> R.string.pokedex_tier_gold
        MedalTier.PLATINUM -> R.string.pokedex_tier_platinum
        MedalTier.DIAMOND -> R.string.pokedex_tier_diamond
    },
)

/** El color base de cada metal. Los mismos en tema claro y oscuro: un oro es un oro. */
internal fun tierColor(tier: MedalTier): Color = when (tier) {
    MedalTier.NONE -> Color(0xFF8A8D91)
    MedalTier.IRON -> Color(0xFF6E7076)
    MedalTier.BRONZE -> Color(0xFFB07A45)
    MedalTier.SILVER -> Color(0xFFB9BEC7)
    MedalTier.GOLD -> Color(0xFFE0B341)
    MedalTier.PLATINUM -> Color(0xFFD5E0E6)
    MedalTier.DIAMOND -> Color(0xFF7FD7F2)
}

// --- El dibujo de la medalla -----------------------------------------------

/**
 * Una medalla dibujada, distinta para cada logro.
 *
 * No hay dos iguales porque la cinta, el motivo del centro y el numero de puntas
 * salen del identificador del logro: la de Doñana siempre es la de Doñana, y no
 * se parece a la de Monfragüe. El metal es lo unico que cambia con el progreso;
 * sin medalla se dibuja apagada y con el borde a trazos, que es "esta por
 * ganar", no "no existe".
 */
@Composable
internal fun MedalArt(medal: Achievement, size: Dp, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val earned = medal.tier.isEarned
    val metal = tierColor(medal.tier)
    val design = remember(medal.id) { MedalDesign.of(medal) }
    val initials = medalInitials(medal)
    val labelStyle = TextStyle(
        fontSize = (size.value * 0.22f).sp,
        fontWeight = FontWeight.Bold,
        color = if (earned) Color(0xFF2B2B2B).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.55f),
        textAlign = TextAlign.Center,
    )

    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val center = Offset(w / 2f, w * 0.58f)
        val radius = w * 0.34f

        // La cinta: dos bandas que bajan del borde superior hacia la medalla.
        val ribbon = if (earned) design.ribbon else design.ribbon.copy(alpha = 0.35f)
        drawRibbon(center, radius, ribbon, design.ribbonSpread)

        // El disco, con brillo arriba a la izquierda. Apagado si no hay medalla.
        val base = if (earned) metal else metal.copy(alpha = 0.35f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(base.lighten(0.45f), base, base.darken(0.35f)),
                center = center + Offset(-radius * 0.3f, -radius * 0.35f),
                radius = radius * 1.4f,
            ),
            radius = radius,
            center = center,
        )
        // El borde: un anillo mas oscuro; a trazos cuando esta por ganar.
        drawCircle(
            color = base.darken(0.45f),
            radius = radius,
            center = center,
            style = Stroke(
                width = radius * 0.08f,
                pathEffect = if (earned) null else PathEffect.dashPathEffect(floatArrayOf(radius * 0.18f, radius * 0.12f)),
            ),
        )
        drawCircle(
            color = base.lighten(0.25f).copy(alpha = if (earned) 0.9f else 0.4f),
            radius = radius * 0.82f,
            center = center,
            style = Stroke(width = radius * 0.03f),
        )

        // El motivo del centro.
        val ink = if (earned) base.darken(0.55f).copy(alpha = 0.75f) else Color.White.copy(alpha = 0.35f)
        drawEmblem(design, center, radius * 0.62f, ink)

        // Las iniciales, encima del motivo.
        if (initials.isNotEmpty()) {
            val measured = measurer.measure(initials, labelStyle)
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(center.x - measured.size.width / 2f, center.y - measured.size.height / 2f),
            )
        }
    }
}

/** Lo que hace unica a cada medalla. Determinista: sale del identificador. */
internal data class MedalDesign(
    val ribbon: Color,
    /** Cuanto se abren las dos bandas de la cinta, 0..1. */
    val ribbonSpread: Float,
    val motif: Motif,
    /** Puntas de la estrella, dientes de la rueda, anillos... segun el motivo. */
    val count: Int,
    val rotationDegrees: Float,
) {
    enum class Motif { STAR, GEAR, RINGS, WAVES, MOUNTAINS, RAYS, LEAF, BIRD }

    companion object {
        private val RIBBONS = listOf(
            Color(0xFFC0392B), Color(0xFF1F5FA8), Color(0xFF1E8449), Color(0xFF8E44AD),
            Color(0xFFD35400), Color(0xFF16A085), Color(0xFF7B241C), Color(0xFF2C3E50),
        )

        fun of(medal: Achievement): MedalDesign {
            val seed = medal.emblemSeed
            var x = seed.toLong() and 0xffffffffL
            fun next(bound: Int): Int {
                // LCG sencillo: solo hace falta que sea determinista y reparta.
                x = (x * 1103515245L + 12345L) and 0x7fffffffL
                return ((x shr 8) % bound).toInt()
            }
            val motif = when (medal.kind) {
                AchievementKind.COLLECTOR -> Motif.BIRD
                AchievementKind.PLACE -> listOf(Motif.WAVES, Motif.MOUNTAINS, Motif.LEAF, Motif.RAYS)[next(4)]
                AchievementKind.ZONE -> listOf(Motif.STAR, Motif.GEAR, Motif.RINGS, Motif.RAYS)[next(4)]
                AchievementKind.FAMILY -> listOf(Motif.LEAF, Motif.STAR, Motif.RINGS, Motif.GEAR)[next(4)]
            }
            return MedalDesign(
                ribbon = RIBBONS[next(RIBBONS.size)],
                ribbonSpread = 0.35f + next(40) / 100f,
                motif = motif,
                count = 5 + next(4),
                rotationDegrees = next(360).toFloat(),
            )
        }
    }
}

private fun DrawScope.drawRibbon(center: Offset, radius: Float, color: Color, spread: Float) {
    val top = 0f
    val width = radius * 0.42f
    val reach = center.y - radius * 0.25f
    for (side in listOf(-1f, 1f)) {
        val path = Path().apply {
            moveTo(center.x + side * radius * spread - width / 2f, top)
            lineTo(center.x + side * radius * spread + width / 2f, top)
            lineTo(center.x + side * width * 0.6f, reach)
            lineTo(center.x - side * width * 0.2f, reach)
            close()
        }
        drawPath(path, color = if (side < 0) color else color.darken(0.18f))
    }
}

private fun DrawScope.drawEmblem(design: MedalDesign, center: Offset, radius: Float, ink: Color) {
    val stroke = Stroke(width = radius * 0.1f, cap = StrokeCap.Round)
    rotate(design.rotationDegrees, pivot = center) {
        when (design.motif) {
            MedalDesign.Motif.STAR -> drawPath(starPath(center, radius, radius * 0.45f, design.count), color = ink)
            MedalDesign.Motif.GEAR -> {
                drawCircle(ink, radius * 0.55f, center, style = stroke)
                repeat(design.count * 2) { i ->
                    val angle = 2 * PI * i / (design.count * 2)
                    val from = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (radius * 0.6f)
                    val to = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * radius
                    drawLine(ink, from, to, strokeWidth = radius * 0.14f, cap = StrokeCap.Round)
                }
            }

            MedalDesign.Motif.RINGS -> repeat(3) { i ->
                drawCircle(ink, radius * (0.4f + 0.3f * i), center, style = Stroke(width = radius * 0.07f))
            }

            MedalDesign.Motif.RAYS -> repeat(design.count * 2) { i ->
                val angle = 2 * PI * i / (design.count * 2)
                val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                drawLine(ink, center + dir * (radius * 0.35f), center + dir * radius, strokeWidth = radius * 0.08f, cap = StrokeCap.Round)
            }

            MedalDesign.Motif.WAVES -> repeat(3) { i ->
                val y = center.y + radius * (-0.45f + 0.45f * i)
                val path = Path().apply {
                    moveTo(center.x - radius, y)
                    var xPos = center.x - radius
                    var up = true
                    while (xPos < center.x + radius - 1f) {
                        val nextX = xPos + radius / 2f
                        quadraticTo(xPos + radius / 4f, y + (if (up) -1f else 1f) * radius * 0.22f, nextX, y)
                        xPos = nextX
                        up = !up
                    }
                }
                drawPath(path, ink, style = Stroke(width = radius * 0.09f, cap = StrokeCap.Round))
            }

            MedalDesign.Motif.MOUNTAINS -> {
                val path = Path().apply {
                    moveTo(center.x - radius, center.y + radius * 0.6f)
                    lineTo(center.x - radius * 0.45f, center.y - radius * 0.3f)
                    lineTo(center.x - radius * 0.1f, center.y + radius * 0.2f)
                    lineTo(center.x + radius * 0.35f, center.y - radius * 0.7f)
                    lineTo(center.x + radius, center.y + radius * 0.6f)
                    close()
                }
                drawPath(path, ink)
            }

            MedalDesign.Motif.LEAF -> {
                val path = Path().apply {
                    moveTo(center.x, center.y + radius)
                    quadraticTo(center.x - radius * 1.1f, center.y, center.x, center.y - radius)
                    quadraticTo(center.x + radius * 1.1f, center.y, center.x, center.y + radius)
                    close()
                }
                drawPath(path, ink)
                drawLine(ink.darken(0.3f), center + Offset(0f, radius), center + Offset(0f, -radius * 0.7f), strokeWidth = radius * 0.06f)
            }

            MedalDesign.Motif.BIRD -> {
                // La misma silueta que el icono de la app, en una caja de 100.
                val bird = BIRD_PATH
                val scaleFactor = radius * 2f / 100f
                translate(center.x - radius, center.y - radius) {
                    scale(scaleFactor, scaleFactor, pivot = Offset.Zero) {
                        drawPath(bird, ink)
                    }
                }
            }
        }
    }
}

private fun starPath(center: Offset, outer: Float, inner: Float, points: Int): Path = Path().apply {
    for (i in 0 until points * 2) {
        val r = if (i % 2 == 0) outer else inner
        val angle = -PI / 2 + PI * i / points
        val point = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * r
        if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
    }
    close()
}

private val BIRD_PATH: Path by lazy {
    PathParser().parsePathString(
        "M 22,66 C 14,72 8,78 4,84 C 8,74 12,62 22,52 C 24,57 24,62 22,66 Z " +
            "M 18,58 C 18,40 32,30 52,32 C 66,34 78,44 78,58 C 78,74 64,84 48,84 C 32,84 18,74 18,58 Z " +
            "M 50,36 A 16,16 0 1 1 82,36 A 16,16 0 1 1 50,36 Z M 78,32 L 95,37 L 78,42 Z",
    ).toPath()
}

/** Dos letras del nombre, para que la medalla se reconozca de lejos. Ninguna para el ave. */
private fun medalInitials(medal: Achievement): String {
    if (medal.kind == AchievementKind.COLLECTOR) return ""
    val words = medal.title.split(' ', '-', '\'').filter { it.length > 2 && it[0].isLetter() }
    return when {
        words.size >= 2 -> "${words[0][0]}${words[1][0]}".uppercase()
        words.size == 1 -> words[0].take(2).uppercase()
        else -> medal.title.take(2).uppercase()
    }
}

private fun Color.lighten(amount: Float): Color = Color(
    red = red + (1f - red) * amount,
    green = green + (1f - green) * amount,
    blue = blue + (1f - blue) * amount,
    alpha = alpha,
)

private fun Color.darken(amount: Float): Color = Color(
    red = red * (1f - amount),
    green = green * (1f - amount),
    blue = blue * (1f - amount),
    alpha = alpha,
)

private operator fun Offset.times(factor: Float): Offset = Offset(x * factor, y * factor)

@Suppress("unused")
private fun Size.shorter(): Float = if (abs(width) < abs(height)) width else height

// --- Previews -------------------------------------------------------------

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun AchievementsPreview() {
    val zone = dev.pelayo.aves.core.data.PokedexZone(dev.pelayo.aves.core.data.ZoneLevel.PROVINCE, "ESP.3.1_1", "Asturias")
    AvesTheme {
        AchievementsContent(
            state = AchievementsUiState(
                isLoading = false,
                medals = listOf(
                    Achievement("collector", AchievementKind.COLLECTOR, "", 41, 6_419, MedalScale.GLOBAL),
                    Achievement("zone:1", AchievementKind.ZONE, "Asturias", 38, 310, MedalScale.ZONE, zone),
                    Achievement("zone:2", AchievementKind.PLACE, "Doñana", 12, 380, MedalScale.ZONE),
                    Achievement("zone:3", AchievementKind.PLACE, "Delta del Ebro", 0, null, MedalScale.ZONE),
                    Achievement("family:Turdidae", AchievementKind.FAMILY, "Turdidae", 6, 12, MedalScale.ZONE),
                    Achievement("family:Paridae", AchievementKind.FAMILY, "Paridae", 5, 5, MedalScale.ZONE),
                ),
            ),
            onSelect = {},
            onRetry = {},
        )
    }
}
