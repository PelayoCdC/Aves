package dev.pelayo.aves.feature.review

import dev.pelayo.aves.core.database.entity.ObservationEntity
import dev.pelayo.aves.core.model.Species

/**
 * Logica pura de la bandeja de revision.
 *
 * Vive fuera del ViewModel a proposito: es la parte que decide que ve el usuario
 * y en que orden, y esa es justamente la parte que hay que poder probar sin
 * Android, sin Room y sin corrutinas.
 */

/**
 * Cuanto se fia el modelo de si mismo, en tres tramos.
 *
 * Se agrupa en niveles y no se deja el numero suelto porque un porcentaje no
 * dice nada por si solo: 66% y 95% son dos situaciones distintas para el
 * observador (una hay que comprobarla, la otra casi no), y la interfaz tiene que
 * hacer notar esa diferencia sin obligar a interpretar decimales.
 */
enum class ConfidenceLevel {
    /** Por debajo del umbral util: confirmar esto a ciegas ensucia la coleccion. */
    DOUBTFUL,
    PROBABLE,
    SOLID,
    ;

    companion object {
        /**
         * Los cortes son deliberadamente conservadores. Un falso positivo que
         * entra en la coleccion cuesta mucho mas que un falso negativo que se
         * queda en la bandeja: lo segundo se revisa luego, lo primero contamina
         * el registro y el usuario deja de fiarse de todo el conjunto.
         */
        const val SOLID_THRESHOLD = 0.85f
        const val PROBABLE_THRESHOLD = 0.65f

        fun of(confidence: Float): ConfidenceLevel = when {
            confidence >= SOLID_THRESHOLD -> SOLID
            confidence >= PROBABLE_THRESHOLD -> PROBABLE
            else -> DOUBTFUL
        }
    }
}

/**
 * Una deteccion esperando el veredicto del usuario, ya cruzada con el catalogo.
 *
 * Lleva las dos confianzas porque son cosas distintas: [confidence] es la que se
 * ensena (la ajustada por contexto, que es sobre la que se decide) y
 * [rawConfidence] se conserva para poder explicar despues por que el prior
 * geo-temporal subio o bajo una deteccion.
 */
data class PendingObservation(
    val observationId: Long,
    val speciesId: String,
    val commonName: String,
    val scientificName: String,
    val confidence: Float,
    val rawConfidence: Float,
    val timestamp: Long,
    /**
     * Ruta del recorte de audio que provoco la deteccion, o null si no se
     * guardo. Viaja hasta aqui porque es la prueba: sin ella, la bandeja pide un
     * veredicto sobre algo que el usuario no puede oir.
     */
    val mediaPath: String? = null,
    /**
     * false cuando el catalogo no conoce el speciesId. Se muestra igualmente,
     * ver [mergePendingWithSpecies].
     */
    val isResolved: Boolean = true,
) {
    val level: ConfidenceLevel get() = ConfidenceLevel.of(confidence)
}

/** Nombre de relleno cuando el catalogo no sabe de que especie hablamos. */
internal const val UNKNOWN_SPECIES_NAME = "Especie sin catalogar"

/**
 * Cruza las observaciones pendientes con el catalogo de especies y las ordena.
 *
 * Orden: confianza descendente. La bandeja es una cola de trabajo, y lo que el
 * modelo tiene mas claro es lo que se despacha de un vistazo; dejar lo dudoso
 * abajo evita que el usuario se canse antes de llegar a lo facil. Los empates se
 * rompen por fecha (lo reciente primero) y luego por id, para que dos listas con
 * los mismos datos salgan siempre iguales y la LazyColumn no baile.
 *
 * Una observacion cuya especie NO esta en el catalogo no se descarta: se muestra
 * con un nombre de relleno. Ocultarla seria peor que ensenarla mal, porque
 * quedaria pendiente para siempre sin que el usuario pueda ni rechazarla.
 */
fun mergePendingWithSpecies(
    observations: List<ObservationEntity>,
    species: List<Species>,
): List<PendingObservation> {
    val byId = species.associateBy(Species::speciesId)

    return observations
        .map { observation ->
            val match = byId[observation.speciesId]
            PendingObservation(
                observationId = observation.id,
                speciesId = observation.speciesId,
                commonName = match?.commonName ?: UNKNOWN_SPECIES_NAME,
                scientificName = match?.scientificName ?: observation.speciesId,
                confidence = observation.adjustedConfidence,
                rawConfidence = observation.rawConfidence,
                timestamp = observation.timestamp,
                mediaPath = observation.mediaPath,
                isResolved = match != null,
            )
        }
        .sortedWith(
            compareByDescending<PendingObservation> { it.confidence }
                .thenByDescending { it.timestamp }
                .thenBy { it.observationId },
        )
}

/** Que hizo el usuario con una deteccion. */
enum class ReviewDecision {
    CONFIRMED,
    REJECTED,
}

/** Una decision tomada, con lo necesario para revertirla y para explicarla. */
data class ReviewAction(
    val observation: PendingObservation,
    val decision: ReviewDecision,
)

/**
 * El mecanismo de deshacer.
 *
 * Existe porque revisar es un gesto rapido y repetitivo: se despachan diez
 * tarjetas seguidas casi sin mirar y la undecima se pulsa mal. Sin deshacer, ese
 * error o es permanente o obliga a ir a buscar la observacion a otra pantalla
 * para arreglarla, y el coste de equivocarse acaba haciendo que el usuario
 * revise despacio y con miedo, que es justo lo contrario de lo que queremos.
 *
 * La capacidad por defecto es 1 porque el aviso en pantalla solo puede hablar de
 * una decision a la vez; se deja configurable para poder probar el desalojo.
 *
 * No es thread-safe: el llamante serializa el acceso.
 */
class UndoStack(private val capacity: Int = 1) {

    init {
        require(capacity >= 1) { "La capacidad tiene que ser al menos 1" }
    }

    private val actions = ArrayDeque<ReviewAction>()

    val size: Int get() = actions.size

    /**
     * Apila una decision.
     *
     * @return la decision que se ha caido por el otro extremo al llenarse la
     *   pila, o null. El llamante DEBE consolidarla: ha dejado de ser reversible
     *   y si nadie la escribe se pierde.
     */
    fun push(action: ReviewAction): ReviewAction? {
        actions.addLast(action)
        return if (actions.size > capacity) actions.removeFirst() else null
    }

    fun peek(): ReviewAction? = actions.lastOrNull()

    /** Saca la ultima decision para revertirla. */
    fun pop(): ReviewAction? = actions.removeLastOrNull()

    /**
     * Vacia la pila y devuelve lo que habia, de la mas antigua a la mas
     * reciente. Se usa al cerrar la pantalla: lo que quede sin deshacer va a
     * disco, no se tira.
     */
    fun drain(): List<ReviewAction> {
        val all = actions.toList()
        actions.clear()
        return all
    }
}
