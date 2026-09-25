package dev.pelayo.aves.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.SpeciesRepository
import dev.pelayo.aves.core.database.entity.ObservationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewUiState(
    val isLoading: Boolean = true,
    val pending: List<PendingObservation> = emptyList(),
    /** La ultima decision, mientras siga siendo reversible. null = nada que deshacer. */
    val undoable: ReviewAction? = null,
)

/**
 * La bandeja de revision: la frontera entre "lo que la maquina cree" y "lo que
 * yo vi".
 *
 * El modelo se equivoca, y si todo lo que cree oir entrara directo en la
 * coleccion, la coleccion dejaria de significar nada para un observador. Aqui el
 * usuario dicta sentencia, una por una.
 *
 * Los repositorios entran por constructor porque en esta fase no hay Hilt: quien
 * construye la pantalla es quien los tiene (ver `IdentifyViewModel`, mismo
 * patron con el clasificador).
 */
class ReviewViewModel(
    private val observationRepository: ObservationRepository,
    private val speciesRepository: SpeciesRepository,
    /** Cuanto dura la posibilidad de deshacer. Parametro para poder probarlo. */
    private val undoWindowMillis: Long = UNDO_WINDOW_MILLIS,
) : ViewModel() {

    /**
     * Observaciones ya decididas pero aun no escritas: desaparecen de la lista
     * al instante aunque la base de datos todavia no lo sepa. Sin esto, la
     * tarjeta seguiria en pantalla durante toda la ventana de deshacer y el
     * gesto no se sentiria como que ha pasado algo.
     */
    private val settledIds = MutableStateFlow<Set<Long>>(emptySet())

    private val undoable = MutableStateFlow<ReviewAction?>(null)

    /**
     * Solo cabe una decision reversible a la vez, que es lo que puede contar el
     * aviso de la pantalla.
     */
    private val undoStack = UndoStack(capacity = 1)

    private var expiryJob: Job? = null

    /**
     * Las escrituras diferidas NO cuelgan de `viewModelScope`: si colgaran, al
     * salir de la pantalla se cancelarian y la decision del usuario se
     * evaporaria. Este scope se deja vivo a proposito; los trabajos que lanza
     * son una escritura corta cada uno.
     */
    private val decisionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val resolved = combine(
        observationRepository.pendingReview(),
        observationRepository.collectedSpeciesIds(),
    ) { rows, collected -> rows to collected.toSet() }
        .map { (rows, collected) ->
            // Una sola consulta al catalogo por emision, no una por fila: se
            // piden de golpe las especies de las filas Y las de sus alternativas.
            val candidates = observationRepository.candidatesFor(rows.map(ObservationEntity::id))
            val ids = (rows.map(ObservationEntity::speciesId) + candidates.values.flatten().map { it.speciesId })
                .distinct()
            mergePendingWithSpecies(rows, speciesRepository.findAll(ids), candidates, collected)
        }

    val uiState: StateFlow<ReviewUiState> =
        combine(resolved, settledIds, undoable) { pending, settled, undo ->
            ReviewUiState(
                isLoading = false,
                pending = pending.filterNot { it.observationId in settled },
                undoable = undo,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
            initialValue = ReviewUiState(),
        )

    fun confirm(observationId: Long) = decide(observationId, ReviewDecision.CONFIRMED)

    fun reject(observationId: Long) = decide(observationId, ReviewDecision.REJECTED)

    /** "No era esto, era lo otro." Confirma la observacion como la alternativa. */
    fun confirmAs(observationId: Long, alternative: Alternative) =
        decide(observationId, ReviewDecision.REASSIGNED, alternative)

    /**
     * Revierte la ultima decision.
     *
     * Es gratis precisamente porque la escritura estaba diferida: no hay que
     * "des-confirmar" nada en la base de datos, basta con no llegar a escribir.
     */
    /**
     * El aviso de deshacer se ha ido sin que se pulsara: la decision ya es
     * firme. Solo si [action] sigue siendo la reversible; si ya se consolido
     * por otro camino (una decision nueva, el reloj de respaldo), no hace nada.
     */
    fun undoWindowClosed(action: ReviewAction) = expire(action)

    fun undo() {
        val action = synchronized(undoStack) { undoStack.pop() } ?: return
        expiryJob?.cancel()
        expiryJob = null
        undoable.compareAndSet(action, null)
        settledIds.update { it - action.observation.observationId }
    }

    /**
     * Aplica una decision de forma optimista y arranca la ventana de deshacer.
     *
     * La escritura se retrasa en vez de escribir ya y revertir despues: con la
     * escritura diferida deshacer es no llegar a escribir, y el peor caso (que
     * el proceso muera dentro de la ventana) deja la observacion como
     * pendiente, que es el fallo seguro: nada entra en la coleccion sin querer.
     */
    private fun decide(observationId: Long, decision: ReviewDecision, reassignTo: Alternative? = null) {
        val target = uiState.value.pending
            .firstOrNull { it.observationId == observationId }
            ?: return

        val action = ReviewAction(target, decision, reassignTo)
        val displaced = synchronized(undoStack) { undoStack.push(action) }

        expiryJob?.cancel()
        // La anterior deja de ser reversible en cuanto llega otra: se consolida.
        displaced?.let(::commit)

        settledIds.update { it + observationId }
        undoable.value = action

        expiryJob = decisionScope.launch {
            delay(undoWindowMillis)
            expire(action)
        }
    }

    private fun expire(action: ReviewAction) {
        val claimed = synchronized(undoStack) {
            if (undoStack.peek() === action) undoStack.pop() else null
        } ?: return

        undoable.compareAndSet(claimed, null)
        commit(claimed)
    }

    private fun commit(action: ReviewAction) {
        val id = action.observation.observationId
        decisionScope.launch {
            when (action.decision) {
                ReviewDecision.CONFIRMED -> observationRepository.confirm(id)
                ReviewDecision.REJECTED -> observationRepository.reject(id)
                ReviewDecision.REASSIGNED -> {
                    val target = action.reassignTo
                    if (target != null) observationRepository.confirmAs(id, target.speciesId) else observationRepository.confirm(id)
                }
            }
        }
    }

    /**
     * Al cerrar no se espera a que venza la ventana: lo que el usuario decidio y
     * no deshizo se escribe ya. Perderlo por salir de la pantalla seria peor que
     * cualquier error que el deshacer pretendia arreglar.
     */
    override fun onCleared() {
        expiryJob?.cancel()
        expiryJob = null
        synchronized(undoStack) { undoStack.drain() }.forEach(::commit)
        super.onCleared()
    }

    companion object {
        /**
         * Respaldo, no el plazo normal. Lo normal es que la decision se escriba
         * cuando el aviso de la pantalla se va ([undoWindowClosed]), dure lo que
         * dure: con los servicios de accesibilidad activos, Material alarga el
         * aviso "corto" muy por encima de los 4 s de siempre. Este reloj solo
         * cubre que la pantalla deje de verse con una decision a medias.
         */
        const val UNDO_WINDOW_MILLIS = 60_000L

        /** Sobrevive a un giro de pantalla sin volver a consultar la base. */
        private const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
    }
}
