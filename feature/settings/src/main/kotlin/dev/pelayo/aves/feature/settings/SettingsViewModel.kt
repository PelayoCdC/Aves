package dev.pelayo.aves.feature.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pelayo.aves.core.data.ObservationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SettingsUiState(
    val isLoading: Boolean = true,
    val settings: AvesSettings = AvesSettings(),
    /** Especies confirmadas, o null si esta pantalla no tiene acceso a la coleccion. */
    val collectedSpecies: Int? = null,
    /** Lo que ocupan los recortes de audio, o null si todavia no se ha medido. */
    val collectionBytes: Long? = null,
)

/**
 * Los ajustes de la app.
 *
 * Sin Hilt: el almacen y los repositorios entran por constructor y los
 * construye quien los tiene, mismo patron que `ReviewViewModel`. Los dos
 * ultimos parametros son opcionales porque la pantalla debe poder abrirse
 * aunque no haya base de datos ni recortes; lo unico irrenunciable es poder
 * cambiar los ajustes y leer la atribucion.
 */
class SettingsViewModel(
    private val store: SettingsStore,
    observationRepository: ObservationRepository? = null,
    /** Carpeta de recortes. Null desactiva el contador de tamaño. */
    private val clipsDirectory: File? = null,
    /** Carpeta de fotos: desde que se guardan a tamano completo, tambien pesan. */
    private val photosDirectory: File? = null,
) : ViewModel() {

    private val collectionBytes = MutableStateFlow<Long?>(null)

    private val collectedSpecies: Flow<Int?> =
        observationRepository?.collectedSpeciesCount() ?: flowOf(null)

    val uiState: StateFlow<SettingsUiState> =
        combine(store.settings, collectedSpecies, collectionBytes) { settings, species, bytes ->
            SettingsUiState(
                isLoading = false,
                settings = settings,
                collectedSpecies = species,
                collectionBytes = bytes,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
            initialValue = SettingsUiState(),
        )

    init {
        refreshCollectionSize()
    }

    fun setContextInfluence(value: Float) = edit { it.copy(contextInfluence = value) }

    /**
     * Apaga la ubicacion de raiz.
     *
     * No es un "no preguntar mas": con esto activado el filtro geo-temporal no se
     * aplica y las observaciones se guardan sin coordenadas. Se paga en precision
     * y el usuario tiene derecho a hacer ese cambio.
     */
    fun setLocationDisabled(disabled: Boolean) = edit { it.copy(locationDisabled = disabled) }

    fun setConfidenceThreshold(value: Float) = edit { it.copy(confidenceThreshold = value) }

    fun setListeningCutoff(minutes: Int) = edit { it.copy(listeningCutoffMinutes = minutes) }

    /**
     * Recorre las carpetas de recortes y de fotos.
     *
     * Se mide bajo peticion y no de forma continua: es E/S sobre potencialmente
     * cientos de ficheros y el dato solo cambia cuando el usuario ha estado
     * escuchando, no mientras mira los ajustes.
     */
    fun refreshCollectionSize() {
        val directories = listOfNotNull(clipsDirectory, photosDirectory)
        if (directories.isEmpty()) return
        viewModelScope.launch {
            val measured = withContext(Dispatchers.IO) {
                try {
                    directories.sumOf(::directorySizeBytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    // Un contador es informativo: que falle no puede impedir
                    // abrir los ajustes.
                    Log.w(TAG, "No se pudo medir lo que ocupa la coleccion", e)
                    null
                }
            }
            collectionBytes.value = measured
        }
    }

    private fun edit(transform: (AvesSettings) -> AvesSettings) {
        viewModelScope.launch { store.update(transform) }
    }

    private companion object {
        const val TAG = "SettingsViewModel"

        /** Sobrevive a un giro de pantalla sin releer el DataStore. */
        const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
    }
}

/** Suma el tamaño de todos los ficheros bajo [directory], recursivamente. */
internal fun directorySizeBytes(directory: File): Long =
    if (!directory.exists()) {
        0L
    } else {
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
