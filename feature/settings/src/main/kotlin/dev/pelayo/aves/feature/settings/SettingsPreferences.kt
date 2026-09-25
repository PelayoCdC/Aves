package dev.pelayo.aves.feature.settings

import dev.pelayo.aves.core.designsystem.theme.AvesSkin

import kotlin.math.roundToInt

/**
 * Los ajustes que el usuario puede tocar, ya validados.
 *
 * Es un unico objeto y no cuatro valores sueltos porque la pantalla los lee
 * juntos y porque asi la validacion tiene un solo punto de entrada
 * ([sanitized]). Todo lo que entre desde disco pasa por ahi: un valor guardado
 * por una version anterior, o corrompido, no puede colarse hasta el modelo.
 */
data class AvesSettings(
    /** Cuanto pesa el prior geo-temporal frente a la salida cruda del modelo. */
    val contextInfluence: Float = DEFAULT_CONTEXT_INFLUENCE,
    /** Desactiva el uso de ubicacion por completo. */
    val locationDisabled: Boolean = false,
    /** Por debajo de esto una deteccion no se muestra. */
    val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
    /** Minutos hasta que la escucha se para sola. [NO_CUTOFF] la deja indefinida. */
    val listeningCutoffMinutes: Int = DEFAULT_LISTENING_CUTOFF_MINUTES,
    /** La piel de la app, por su nombre (`AvesSkin`). Texto y no enum para que el DataStore no sepa de temas. */
    val skin: String = AvesSkin.SYSTEM.name,
    /**
     * Ensenar el porcentaje de confianza junto a cada especie. Apagado de
     * fabrica: una lista corta de nombres seguros, al estilo Merlin; un "23 %"
     * al lado hace desconfiar de la lista entera. Encendido, la lista es la
     * misma (se sigue filtrando por el umbral) con el numero al lado.
     */
    val showConfidence: Boolean = false,
) {

    /** Version de este objeto con todos los valores dentro de rango. */
    fun sanitized(): AvesSettings = copy(
        contextInfluence = sanitizeContextInfluence(contextInfluence),
        confidenceThreshold = sanitizeConfidenceThreshold(confidenceThreshold),
        listeningCutoffMinutes = sanitizeListeningCutoff(listeningCutoffMinutes),
        skin = AvesSkin.fromName(skin).name,
    )

    companion object {
        val CONTEXT_INFLUENCE_RANGE = 0f..1f

        /**
         * A la mitad y no al maximo a proposito: el prior es una estadistica, no
         * una verdad. Si mandara del todo, un divagante autentico quedaria
         * sepultado bajo lo que "deberia" haber en la zona, que es justo lo mas
         * emocionante que puede pasar con esta app. Mismo valor de fabrica que
         * `ListeningSettings.DEFAULT_CONTEXT_INFLUENCE`.
         */
        const val DEFAULT_CONTEXT_INFLUENCE = 0.5f

        /**
         * Ni 0 ni 1 son alcanzables: a 0 la lista serian 6.419 especies de ruido,
         * y a 1 no pasaria nunca nada. Un extremo inutil en un control deslizante
         * solo sirve para que alguien lo pruebe y crea que la app esta rota.
         */
        val CONFIDENCE_THRESHOLD_RANGE = 0.05f..0.95f

        /**
         * 0,35: el que recomienda el propio equipo de BirdNET
         * (`defaultConfidenceThreshold` en su model_config.json). Es el mismo
         * valor de fabrica que usa el estabilizador de la escucha; este ajuste
         * es la unica forma de cambiarlo.
         */
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.35f

        val LISTENING_CUTOFF_RANGE = 0..120
        const val LISTENING_CUTOFF_STEP_MINUTES = 5

        /** Escucha sin limite. Solo la para el usuario. */
        const val NO_CUTOFF = 0

        /**
         * Una hora: lo que dura el coro del amanecer, que es el caso de uso que
         * justifica la escucha en segundo plano. Es el mismo valor por defecto
         * que aplica el servicio si no se ha guardado ningun ajuste.
         */
        const val DEFAULT_LISTENING_CUTOFF_MINUTES = 60

        fun sanitizeContextInfluence(value: Float): Float =
            if (value.isNaN()) DEFAULT_CONTEXT_INFLUENCE else value.coerceIn(CONTEXT_INFLUENCE_RANGE)

        fun sanitizeConfidenceThreshold(value: Float): Float =
            if (value.isNaN()) {
                DEFAULT_CONFIDENCE_THRESHOLD
            } else {
                value.coerceIn(CONFIDENCE_THRESHOLD_RANGE)
            }

        /**
         * Ajusta el corte al escalon mas cercano.
         *
         * Un valor negativo NO se interpreta como "sin corte" aunque saturar
         * hacia abajo llevaria ahi: eso convertiria un dato corrupto en una
         * escucha indefinida, y el fallo seguro es el contrario. Se vuelve al
         * valor por defecto.
         */
        fun sanitizeListeningCutoff(minutes: Int): Int {
            if (minutes < 0) return DEFAULT_LISTENING_CUTOFF_MINUTES
            if (minutes == NO_CUTOFF) return NO_CUTOFF

            val bounded = minutes.coerceIn(LISTENING_CUTOFF_RANGE)
            val step = LISTENING_CUTOFF_STEP_MINUTES
            val snapped = (bounded.toFloat() / step).roundToInt() * step
            // Redondear 1 o 2 minutos hacia abajo daria 0, que aqui significa
            // justo lo contrario de lo que el usuario pidio.
            return snapped.coerceIn(step, LISTENING_CUTOFF_RANGE.last)
        }
    }
}
