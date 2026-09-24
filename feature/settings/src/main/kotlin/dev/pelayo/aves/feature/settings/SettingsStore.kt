package dev.pelayo.aves.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.IOException

/**
 * Donde viven los ajustes.
 *
 * Es una interfaz y no la clase de DataStore directamente para que el
 * ViewModel se pueda ejercitar sin fichero ni Context, igual que
 * `OccurrenceSource` en `:core:data` con la red.
 */
interface SettingsStore {
    val settings: Flow<AvesSettings>

    /**
     * Lee, transforma y escribe de forma atomica. Se pasa una funcion en vez del
     * objeto completo porque dos pantallas podrian estar tocando ajustes
     * distintos a la vez y con un `save(nuevo)` una ganaria pisando a la otra.
     */
    suspend fun update(transform: (AvesSettings) -> AvesSettings)
}

private val Context.avesSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "aves_settings",
)

/** Crea el almacen real. El `applicationContext` evita retener una Activity. */
fun settingsStore(context: Context): SettingsStore =
    DataStoreSettingsStore(context.applicationContext.avesSettingsDataStore)

/**
 * Implementacion sobre Preferences DataStore.
 *
 * Todo lo que sale del disco pasa por `sanitized()`: un fichero escrito por una
 * version anterior, o a medio escribir, no puede propagar un valor imposible
 * hasta el clasificador.
 */
internal class DataStoreSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {

    override val settings: Flow<AvesSettings> = dataStore.data
        // Un DataStore ilegible tiene que degradar a los valores por defecto, no
        // tumbar la pantalla de ajustes: sin ella el usuario no puede ni apagar
        // la ubicacion.
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { it.toSettings() }

    override suspend fun update(transform: (AvesSettings) -> AvesSettings) {
        dataStore.edit { preferences ->
            val next = transform(preferences.toSettings()).sanitized()
            preferences[KEY_CONTEXT_INFLUENCE] = next.contextInfluence
            preferences[KEY_LOCATION_DISABLED] = next.locationDisabled
            preferences[KEY_CONFIDENCE_THRESHOLD] = next.confidenceThreshold
            preferences[KEY_LISTENING_CUTOFF] = next.listeningCutoffMinutes
            preferences[KEY_SKIN] = next.skin
        }
    }

    private fun Preferences.toSettings(): AvesSettings = AvesSettings(
        contextInfluence = this[KEY_CONTEXT_INFLUENCE] ?: AvesSettings.DEFAULT_CONTEXT_INFLUENCE,
        locationDisabled = this[KEY_LOCATION_DISABLED] ?: false,
        confidenceThreshold = this[KEY_CONFIDENCE_THRESHOLD]
            ?: AvesSettings.DEFAULT_CONFIDENCE_THRESHOLD,
        listeningCutoffMinutes = this[KEY_LISTENING_CUTOFF]
            ?: AvesSettings.DEFAULT_LISTENING_CUTOFF_MINUTES,
        skin = this[KEY_SKIN] ?: AvesSettings().skin,
    ).sanitized()

    private companion object {
        val KEY_CONTEXT_INFLUENCE = floatPreferencesKey("context_influence")
        val KEY_LOCATION_DISABLED = booleanPreferencesKey("location_disabled")
        val KEY_CONFIDENCE_THRESHOLD = floatPreferencesKey("confidence_threshold")
        val KEY_LISTENING_CUTOFF = intPreferencesKey("listening_cutoff_minutes")
        val KEY_SKIN = stringPreferencesKey("skin")
    }
}

/**
 * Almacen en memoria, para previsualizaciones y pruebas.
 *
 * No persiste nada a proposito: si lo hiciera, una prueba dejaria residuos en el
 * dispositivo que ejecuta la siguiente.
 */
class InMemorySettingsStore(initial: AvesSettings = AvesSettings()) : SettingsStore {
    private val state = MutableStateFlow(initial.sanitized())
    override val settings: Flow<AvesSettings> = state.asStateFlow()

    override suspend fun update(transform: (AvesSettings) -> AvesSettings) {
        state.update { transform(it).sanitized() }
    }
}
