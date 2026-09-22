package dev.pelayo.aves.feature.pokedex

import dev.pelayo.aves.core.data.PokedexZone
import dev.pelayo.aves.core.data.ZoneLevel
import dev.pelayo.aves.core.model.Species

/*
 * Los logros: medallas por lo que se lleva de cada zona, de cada familia y del
 * total. Kotlin puro, sin Android ni Room, para poder probar los cortes en la
 * JVM: aqui es donde una medalla de oro puede salir por un redondeo.
 */

/** De hierro a diamante. [NONE] es "todavia no". */
enum class MedalTier {
    NONE,
    IRON,
    BRONZE,
    SILVER,
    GOLD,
    PLATINUM,
    DIAMOND,
    ;

    val isEarned: Boolean get() = this != NONE

    val next: MedalTier? get() = entries.getOrNull(ordinal + 1)
}

/**
 * A partir de que fraccion se da cada metal.
 *
 * Dos escalas porque "todas las aves del planeta" y "las aves de Asturias" no
 * se miden igual: el 20 % de las 6.400 especies que sabe el modelo es una vida
 * entera de viajes; el 20 % de las 300 de una provincia es una primavera.
 */
class MedalScale(private val thresholds: List<Float>) {

    init {
        require(thresholds.size == MedalTier.entries.size - 1) { "Un umbral por metal" }
        require(thresholds == thresholds.sorted()) { "Los umbrales van de menos a mas" }
    }

    fun tierFor(fraction: Float): MedalTier {
        var tier = MedalTier.NONE
        for ((index, threshold) in thresholds.withIndex()) {
            if (fraction >= threshold) tier = MedalTier.entries[index + 1] else break
        }
        return tier
    }

    /** Que fraccion pide [tier]. Null para [MedalTier.NONE]. */
    fun thresholdOf(tier: MedalTier): Float? = if (tier == MedalTier.NONE) null else thresholds[tier.ordinal - 1]

    companion object {
        /** Zonas y familias: listas de decenas o cientos de especies. */
        val ZONE = MedalScale(listOf(0.05f, 0.15f, 0.30f, 0.50f, 0.75f, 0.95f))

        /** El total de lo que el modelo sabe identificar: miles. */
        val GLOBAL = MedalScale(listOf(0.005f, 0.01f, 0.025f, 0.05f, 0.10f, 0.20f))
    }
}

enum class AchievementKind {
    /** Cuanto llevas de todo lo que el modelo sabe identificar. */
    COLLECTOR,

    /** Una provincia, una region o un pais. */
    ZONE,

    /** Un lugar destacado: Doñana, el delta del Ebro. */
    PLACE,

    /** Una familia entera: todos los turdidos, todos los paridos. */
    FAMILY,
}

/**
 * Una medalla, ganada o por ganar.
 *
 * @param total cuantas especies hay en juego, o null si aun no se sabe (la lista
 *   de la zona no se ha descargado). Sin total no hay metal, hay "sin datos".
 * @param emblemSeed lo que hace unico el dibujo de cada medalla: se deriva del
 *   identificador, asi que la misma medalla se dibuja siempre igual.
 */
data class Achievement(
    val id: String,
    val kind: AchievementKind,
    val title: String,
    val collected: Int,
    val total: Int?,
    val scale: MedalScale,
    val zone: PokedexZone? = null,
) {
    val hasData: Boolean get() = total != null && total > 0

    val fraction: Float
        get() = if (total == null || total <= 0) 0f else (collected.toFloat() / total).coerceIn(0f, 1f)

    val tier: MedalTier get() = if (hasData) scale.tierFor(fraction) else MedalTier.NONE

    /** Cuantas especies faltan para el siguiente metal. Null si ya es diamante o no hay datos. */
    val remainingForNext: Int?
        get() {
            val size = total ?: return null
            val next = tier.next ?: return null
            val needed = kotlin.math.ceil(scale.thresholdOf(next)!! * size.toDouble()).toInt()
            return (needed - collected).coerceAtLeast(0)
        }

    val emblemSeed: Int get() = id.hashCode()
}

/** Una zona candidata a medalla, con su lista si esta en el movil. */
data class ZoneChecklist(
    val zone: PokedexZone,
    /** Los identificadores de las especies de la zona, o null si no hay lista. */
    val speciesIds: Set<String>?,
)

/**
 * Todas las medallas, ganadas primero (del mejor metal al peor) y las que faltan
 * despues.
 *
 * @param collectedIds las especies de la coleccion.
 * @param detectableTotal cuantas especies sabe identificar algun modelo.
 * @param zones las zonas con medalla propia: la del usuario a sus niveles y los
 *   lugares destacados.
 * @param detectableByFamily las especies que el modelo sabe identificar, por
 *   familia: el total de cada medalla de familia.
 */
internal fun buildAchievements(
    collectedIds: Set<String>,
    detectableTotal: Int,
    zones: List<ZoneChecklist>,
    detectableByFamily: Map<String, List<Species>>,
): List<Achievement> {
    val medals = ArrayList<Achievement>()

    medals += Achievement(
        id = "collector",
        kind = AchievementKind.COLLECTOR,
        title = "",
        collected = collectedIds.size,
        total = detectableTotal.takeIf { it > 0 },
        scale = MedalScale.GLOBAL,
    )

    for (entry in zones) {
        val ids = entry.speciesIds
        medals += Achievement(
            id = "zone:${entry.zone.id}",
            kind = if (entry.zone.level == ZoneLevel.PLACE) AchievementKind.PLACE else AchievementKind.ZONE,
            title = entry.zone.name,
            collected = ids?.count { it in collectedIds } ?: 0,
            total = ids?.size,
            scale = MedalScale.ZONE,
            zone = entry.zone,
        )
    }

    // Las familias solo cuando hay algo: son cientos, y una pared de medallas
    // grises de familias tropicales no anima a nadie. Con una especie ya se ve
    // (aunque sea de hierro) y se entiende que hay mas por ganar.
    for ((family, members) in detectableByFamily) {
        if (members.size < MIN_FAMILY_SIZE) continue
        val collected = members.count { it.speciesId in collectedIds }
        if (collected == 0) continue
        medals += Achievement(
            id = "family:$family",
            kind = AchievementKind.FAMILY,
            title = family,
            collected = collected,
            total = members.size,
            scale = MedalScale.ZONE,
        )
    }

    return medals.sortedWith(
        compareByDescending<Achievement> { it.tier.isEarned }
            .thenByDescending { it.tier }
            .thenBy { it.kind }
            .thenByDescending { it.fraction }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
    )
}

/**
 * Una familia de dos especies se completa con un paseo: no es un logro. Con
 * cinco ya hay que buscarlas.
 */
internal const val MIN_FAMILY_SIZE = 5
