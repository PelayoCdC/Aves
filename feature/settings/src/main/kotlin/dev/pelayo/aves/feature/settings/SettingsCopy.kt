package dev.pelayo.aves.feature.settings

import kotlin.math.roundToInt

/**
 * Los textos que acompañan a cada ajuste.
 *
 * Viven fuera de los composables por dos razones: se pueden probar sin arrancar
 * Compose, y obligan a que cada control tenga una frase que explique en lenguaje
 * llano que hace. Un deslizador llamado "influencia del contexto" sin esa frase
 * no significa nada para nadie que no haya escrito el codigo.
 */

/** Tramos en los que el valor del contexto cambia de significado para el usuario. */
enum class ContextInfluenceTier {
    /** El contexto no interviene. */
    OFF,
    LIGHT,
    BALANCED,
    DOMINANT;

    companion object {
        const val LIGHT_THRESHOLD = 0.05f
        const val BALANCED_THRESHOLD = 0.35f
        const val DOMINANT_THRESHOLD = 0.75f

        /** Los umbrales son inclusivos por abajo, como `ConfidenceLevel` en revision. */
        fun of(value: Float): ContextInfluenceTier = when {
            value >= DOMINANT_THRESHOLD -> DOMINANT
            value >= BALANCED_THRESHOLD -> BALANCED
            value >= LIGHT_THRESHOLD -> LIGHT
            else -> OFF
        }
    }
}

/** Tramos del umbral de confianza. */
enum class ConfidenceGate {
    PERMISSIVE,
    BALANCED,
    STRICT;

    companion object {
        /** Por debajo del 0,3 se esta por debajo de lo que el propio BirdNET recomienda. */
        const val BALANCED_THRESHOLD = 0.3f
        const val STRICT_THRESHOLD = 0.6f

        fun of(value: Float): ConfidenceGate = when {
            value >= STRICT_THRESHOLD -> STRICT
            value >= BALANCED_THRESHOLD -> BALANCED
            else -> PERMISSIVE
        }
    }
}

/** "50 %". El espacio antes del signo es el uso correcto en español. */
fun percentLabel(value: Float): String {
    val safe = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
    return "${(safe * 100).roundToInt()} %"
}

/** Frase que explica que hace la influencia del contexto en el valor dado. */
fun contextInfluenceLine(value: Float): String {
    val tier = ContextInfluenceTier.of(AvesSettings.sanitizeContextInfluence(value))
    val explanation = when (tier) {
        ContextInfluenceTier.OFF ->
            "El contexto no interviene: verás lo que el modelo cree oír, esté o no " +
                "esa especie por aquí. Es lo que quieres al analizar una grabación " +
                "hecha en otro sitio."

        ContextInfluenceTier.LIGHT ->
            "Se corrige un poco con lo que suele haber en la zona, pero lo inesperado " +
                "sigue apareciendo."

        ContextInfluenceTier.BALANCED ->
            "Se equilibra lo que oye el modelo con lo que GBIF registra por aquí en " +
                "esta época del año."

        ContextInfluenceTier.DOMINANT ->
            "Manda lo que se ha visto antes en la zona. Aciertas más con lo común, " +
                "pero una rareza de verdad puede quedar sepultada."
    }
    return "${percentLabel(value)} · $explanation"
}

/** Frase que explica el umbral de confianza en el valor dado. */
fun confidenceThresholdLine(value: Float): String {
    val gate = ConfidenceGate.of(AvesSettings.sanitizeConfidenceThreshold(value))
    val explanation = when (gate) {
        ConfidenceGate.PERMISSIVE ->
            "Pasa casi todo lo que el modelo insinúa. Verás cantos lejanos, y también " +
                "bastante ruido."

        ConfidenceGate.BALANCED ->
            "Punto medio: pasan las detecciones sólidas y alguna dudosa que tendrás " +
                "que juzgar tú."

        ConfidenceGate.STRICT ->
            "Solo lo que el modelo tiene muy claro. Se te escaparán cantos lejanos o " +
                "tapados por el viento."
    }
    return "${percentLabel(value)} · $explanation"
}

/** "45 min", "1 hora", "1 h 30 min" o "Sin corte". */
fun cutoffLabel(minutes: Int): String = when {
    minutes <= AvesSettings.NO_CUTOFF -> "Sin corte"
    minutes < 60 -> "$minutes min"
    minutes == 60 -> "1 hora"
    minutes % 60 == 0 -> "${minutes / 60} horas"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

/** Frase que explica el corte automatico de escucha. */
fun listeningCutoffLine(minutes: Int): String {
    val safe = AvesSettings.sanitizeListeningCutoff(minutes)
    return if (safe == AvesSettings.NO_CUTOFF) {
        "Sin corte · La escucha sigue hasta que la pares tú (con un tope de seguridad " +
            "de 8 horas). Si el móvil se queda grabando en el bolsillo, se nota en la batería."
    } else {
        "${cutoffLabel(safe)} · La escucha se para sola al cumplirse ese tiempo, " +
            "aunque te olvides de ella."
    }
}

/** Recuento con punto de millar, como se escribe en español: "6.419". */
fun groupThousands(count: Int): String {
    val sign = if (count < 0) "-" else ""
    val digits = kotlin.math.abs(count.toLong()).toString()
    return sign + digits.reversed().chunked(3).joinToString(".").reversed()
}

/** "6.419 especies" / "1 especie". */
fun speciesCountLabel(count: Int): String =
    if (count == 1) "1 especie" else "${groupThousands(count)} especies"

/**
 * Tamaño en unidades decimales y con coma decimal.
 *
 * Se calcula con enteros en vez de con `String.format` para que no dependa del
 * Locale del dispositivo: el separador debe ser una coma siempre, y una prueba
 * que pase o falle segun la configuracion regional no vale para nada.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "0 B"
    return when {
        bytes < 1_000L -> "$bytes B"
        bytes < 1_000_000L -> "${bytes / 1_000L} kB"
        bytes < 1_000_000_000L -> withOneDecimal(bytes, 1_000_000L, "MB")
        else -> withOneDecimal(bytes, 1_000_000_000L, "GB")
    }
}

private fun withOneDecimal(bytes: Long, unit: Long, suffix: String): String {
    var whole = bytes / unit
    var tenths = ((bytes % unit) * 10 + unit / 2) / unit
    if (tenths >= 10) {
        whole++
        tenths = 0
    }
    return "$whole,$tenths $suffix"
}

/**
 * Que ocupa la coleccion. Devuelve null cuando no hay nada que contar, para que
 * la pantalla pueda omitir la linea en vez de enseñar un cero sin sentido.
 */
fun collectionSizeLine(speciesCount: Int?, bytes: Long?): String? {
    val parts = buildList {
        if (speciesCount != null) {
            add(
                if (speciesCount == 0) {
                    "Todavía no has confirmado ninguna especie"
                } else {
                    "${speciesCountLabel(speciesCount)} en tu colección"
                },
            )
        }
        // Solo los recortes de audio: es lo unico que pesa de verdad, y ademas
        // es lo unico que el usuario puede querer borrar sin perder la coleccion.
        if (bytes != null) add("${formatBytes(bytes)} en recortes de audio")
    }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}
