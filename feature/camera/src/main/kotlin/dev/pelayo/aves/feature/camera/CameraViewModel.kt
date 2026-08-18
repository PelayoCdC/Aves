package dev.pelayo.aves.feature.camera

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pelayo.aves.core.ml.api.ClassificationResult
import dev.pelayo.aves.core.ml.api.ImageBirdClassifier
import dev.pelayo.aves.core.ml.api.ObservationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.io.IOException

/** Por que no se puede identificar por foto. Cada una se arregla de una forma distinta. */
enum class CameraError {
    /** El build no trae clasificador de imagen. */
    NO_MODEL_IN_BUILD,

    /** Hay codigo pero faltan los ficheros del modelo: toca ejecutar fetch-models.ps1. */
    MODEL_FILES_MISSING,

    /** CameraX no pudo abrir o enlazar la camara. */
    CAMERA_UNAVAILABLE,

    /** La foto no llego a convertirse en algo clasificable. */
    CAPTURE_FAILED,
}

/**
 * Un candidato de la ultima foto, ya listo para pintar.
 *
 * El labelmap de AIY solo trae nombre cientifico, asi que aqui no hay nombre
 * comun que mostrar. Resolverlo es trabajo del catalogo de especies, aguas
 * abajo de esta pantalla.
 */
data class ImageCandidate(
    /** null cuando la clase del modelo no mapea a ninguna especie que sigamos. */
    val speciesId: String?,
    val scientificName: String,
    val confidence: Float,
)

data class CameraUiState(
    /** El modelo se esta cargando. Ocurre una vez por sesion de pantalla. */
    val isWarmingUp: Boolean = false,
    val isClassifying: Boolean = false,
    val candidates: List<ImageCandidate> = emptyList(),
    /**
     * Ya se ha clasificado al menos una foto. Distingue "todavia no has
     * disparado" de "he mirado y no he visto ningun ave", que son dos estados
     * vacios que no se pueden contar igual.
     */
    val hasResult: Boolean = false,
    val error: CameraError? = null,
    val errorDetail: String? = null,
    /** Texto de atribucion del modelo. Lo exige la licencia, no es cortesia. */
    val attribution: String? = null,
    val modelName: String? = null,
    val speciesCount: Int = 0,
    /**
     * Lado en pixeles de la entrada del modelo. Lo declara el MODELO y por eso
     * viaja en el estado: la pantalla escala a lo que le digan, no a un 224
     * cableado que se quedaria mintiendo el dia que se cambie de red.
     */
    val inputSize: Int = CameraViewModel.FALLBACK_INPUT_SIZE,
    val lastInferenceMillis: Long = 0L,
)

/**
 * Identificacion por foto: la via secundaria, la que sirve cuando ves al ave
 * pero no la oyes.
 *
 * El clasificador entra como fabrica y no como instancia, igual que en
 * `IdentifyViewModel`: retiene un modelo mapeado en memoria y solo debe existir
 * mientras la pantalla este viva. La fabrica devuelve null si el build no trae
 * modelo de imagen.
 */
class CameraViewModel(
    private val classifierFactory: () -> ImageBirdClassifier?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var classifier: ImageBirdClassifier? = null
    private var classifyJob: Job? = null

    /**
     * Calentar cuesta cientos de milisegundos y solo hace falta una vez. Se
     * recuerda aqui y no en el estado porque es un detalle del ciclo de vida del
     * interprete, no algo que la pantalla deba saber.
     */
    private var warmedUp = false

    /**
     * Carga el modelo por adelantado.
     *
     * Se llama al conceder el permiso, no al pulsar el disparador: el usuario
     * tarda unos segundos en encuadrar y esos segundos son gratis. Hacerlo al
     * disparar meteria la carga del modelo justo en el unico momento en que la
     * espera se nota.
     */
    fun prepare() {
        if (warmedUp || classifyJob?.isActive == true) return
        val active = obtainClassifier() ?: return

        classifyJob = viewModelScope.launch {
            _uiState.update { it.copy(isWarmingUp = true, error = null, errorDetail = null) }
            try {
                active.warmUp()
                warmedUp = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileNotFoundException) {
                fail(CameraError.MODEL_FILES_MISSING, e)
            } catch (e: IOException) {
                fail(CameraError.MODEL_FILES_MISSING, e)
            } finally {
                _uiState.update { it.copy(isWarmingUp = false) }
            }
        }
    }

    /**
     * Clasifica la foto ya recortada y escalada a [CameraUiState.inputSize].
     *
     * NO toma la propiedad de [bitmap]: no lo recicla. A este tamano son unos
     * 200 KB, y reciclarlo aqui abriria la puerta a que la pantalla intentara
     * pintar despues un bitmap muerto, que si revienta la app. El que si hay que
     * reciclar -la foto original de varios megapixeles- ya se ha soltado antes
     * de llegar aqui.
     */
    fun classify(bitmap: Bitmap, context: ObservationContext) {
        // Un disparo a la vez. El boton se deshabilita mientras tanto, pero un
        // doble toque rapido llegaria igual hasta aqui.
        if (classifyJob?.isActive == true && _uiState.value.isClassifying) return

        val active = obtainClassifier() ?: return

        _uiState.update {
            it.copy(
                isClassifying = true,
                error = null,
                errorDetail = null,
                candidates = emptyList(),
            )
        }

        classifyJob = viewModelScope.launch {
            try {
                if (!warmedUp) {
                    _uiState.update { it.copy(isWarmingUp = true) }
                    active.warmUp()
                    warmedUp = true
                    _uiState.update { it.copy(isWarmingUp = false) }
                }

                val started = System.nanoTime()
                val results = active.classify(bitmap, context)
                val elapsedMillis = (System.nanoTime() - started) / 1_000_000

                _uiState.update {
                    it.copy(
                        candidates = results.toCandidates(),
                        hasResult = true,
                        lastInferenceMillis = elapsedMillis,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileNotFoundException) {
                fail(CameraError.MODEL_FILES_MISSING, e)
            } catch (e: IOException) {
                fail(CameraError.MODEL_FILES_MISSING, e)
            } finally {
                _uiState.update { it.copy(isClassifying = false, isWarmingUp = false) }
            }
        }
    }

    /** La pantalla no pudo abrir o enlazar la camara. */
    fun reportCameraFailure(cause: Throwable) = fail(CameraError.CAMERA_UNAVAILABLE, cause)

    /** El disparo fallo o la foto no se pudo decodificar. */
    fun reportCaptureFailure(cause: Throwable) = fail(CameraError.CAPTURE_FAILED, cause)

    /** Vuelve al estado de encuadre, sin candidatos ni error en pantalla. */
    fun dismissResults() {
        _uiState.update {
            it.copy(candidates = emptyList(), hasResult = false, error = null, errorDetail = null)
        }
    }

    /**
     * Instancia el clasificador la primera vez y publica de paso lo que el
     * modelo declara de si mismo (atribucion y tamano de entrada).
     */
    private fun obtainClassifier(): ImageBirdClassifier? {
        classifier?.let { return it }

        val created = classifierFactory()
        if (created == null) {
            _uiState.update { it.copy(error = CameraError.NO_MODEL_IN_BUILD) }
            return null
        }
        classifier = created

        _uiState.update {
            it.copy(
                attribution = created.descriptor.attributionText,
                modelName = created.descriptor.displayName,
                speciesCount = created.descriptor.speciesCount,
                inputSize = created.inputSize,
            )
        }
        return created
    }

    private fun fail(error: CameraError, cause: Throwable) {
        Log.e(TAG, "Fallo la identificacion por foto: $error", cause)
        _uiState.update {
            it.copy(error = error, errorDetail = cause.message, candidates = emptyList())
        }
    }

    /**
     * Pasa de la salida cruda del modelo a lo que tiene sentido ensenar.
     *
     * Se ordena aqui en vez de fiarse del orden que venga: el contrato de
     * `ImageBirdClassifier` no lo promete, y el dia que una implementacion
     * devuelva los logits tal cual, esta pantalla mentiria sin dar error.
     */
    private fun List<ClassificationResult>.toCandidates(): List<ImageCandidate> = this
        // Sin speciesId no hay nada que anadir a la coleccion. Aqui cae tambien
        // la clase `background` de AIY, que es justo lo que devuelve el modelo
        // cuando en la foto no hay ningun ave.
        .filter { it.speciesId != null && it.adjustedScore >= MIN_CONFIDENCE }
        .sortedByDescending { it.adjustedScore }
        .take(MAX_CANDIDATES)
        .map {
            ImageCandidate(
                speciesId = it.speciesId,
                scientificName = it.rawLabel,
                confidence = it.adjustedScore,
            )
        }

    override fun onCleared() {
        classifyJob?.cancel()
        classifyJob = null
        // Sin esto se queda vivo el modelo mapeado en memoria.
        classifier?.close()
        classifier = null
        super.onCleared()
    }

    companion object {
        private const val TAG = "CameraViewModel"

        /**
         * Cuantos candidatos se ensenan.
         *
         * Tres y no diez: la lista tapa la vista previa, y a partir del tercero
         * lo que se anade no es informacion sino ruido con aspecto de dato.
         */
        const val MAX_CANDIDATES = 3

        /**
         * Por debajo de esto el candidato no se muestra.
         *
         * Un softmax de 965 clases siempre reparte algo entre todas. Ensenar
         * cinco especies al 1 % no ayuda a nadie y hace que la app parezca que
         * adivina, que es exactamente lo que estaria haciendo.
         */
        const val MIN_CONFIDENCE = 0.05f

        /**
         * Lado de entrada mientras no haya modelo del que preguntarlo (previews,
         * flavor sin clasificador). Coincide con MobileNet V2, que es lo que
         * usa AIY birds_V1.
         */
        const val FALLBACK_INPUT_SIZE = 224
    }
}
