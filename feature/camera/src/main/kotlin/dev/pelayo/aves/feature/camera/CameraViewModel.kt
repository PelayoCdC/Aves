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
 * abajo de esta pantalla. [labelIndex] viaja hasta alli porque es lo que el
 * cross-walk necesita para dar con la especie canonica.
 */
data class ImageCandidate(
    /** Provisional, derivado del nombre cientifico. null si la clase no mapea a nada. */
    val speciesId: String?,
    val scientificName: String,
    /** Confianza tras el prior geo-temporal: la que se ensena y sobre la que se decide. */
    val confidence: Float,
    /** Lo que dijo el modelo antes del prior. Se guarda para poder auditarlo. */
    val rawConfidence: Float,
    val labelIndex: Int,
)

/**
 * Lo que sale de la pantalla cuando el usuario elige un candidato: todo lo que
 * hace falta para guardar la observacion, incluida la foto que la justifica.
 *
 * @param photo copia propia de la imagen que vio el modelo (224 px): la
 *   miniatura. Quien la recibe es su dueno.
 * @param fullPhoto el encuadre entero, para poder mirar la foto despues. Nadie
 *   lo recicla a mano, asi que se puede guardar sin prisa.
 */
data class ImageIdentification(
    val candidate: ImageCandidate,
    val modelId: String,
    val context: ObservationContext,
    val photo: Bitmap?,
    val fullPhoto: Bitmap? = null,
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
    val modelId: String? = null,
    val speciesCount: Int = 0,
    /**
     * Lado en pixeles de la entrada del modelo. Lo declara el MODELO y por eso
     * viaja en el estado: la pantalla escala a lo que le digan, no a un 224
     * cableado que se quedaria mintiendo el dia que se cambie de red.
     */
    val inputSize: Int = CameraViewModel.FALLBACK_INPUT_SIZE,
    val lastInferenceMillis: Long = 0L,
    /**
     * La foto elegida de la galeria, si lo que se esta clasificando no viene de
     * la camara. Mientras no sea null, la pantalla la ensena en lugar de la
     * vista previa.
     */
    val pickedImage: Bitmap? = null,
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
     * La precarga va en su propio Job y no comparte el de clasificar porque las
     * dos pueden solaparse: el usuario puede disparar antes de que el modelo
     * termine de cargar. Con un unico campo, la segunda pisaria la referencia a
     * la primera y `warmUp` acabaria ejecutandose dos veces en paralelo.
     */
    private var warmUpJob: Job? = null

    /**
     * Calentar cuesta cientos de milisegundos y solo hace falta una vez. Se
     * recuerda aqui y no en el estado porque es un detalle del ciclo de vida del
     * interprete, no algo que la pantalla deba saber.
     */
    private var warmedUp = false

    /**
     * La ultima foto que vio el modelo y el contexto con el que se clasifico.
     * Se conservan para poder guardarlos con la observacion si el usuario
     * elige un candidato. Este ViewModel es el dueno del bitmap y lo recicla.
     */
    private var lastInput: Bitmap? = null
    private var lastContext: ObservationContext? = null

    /**
     * El encuadre entero de la ultima foto, para guardarlo junto a la miniatura.
     * A diferencia de [lastInput], este NO se recicla a mano: el de la galeria
     * lo esta pintando Compose, y el de la camara puede estar comprimiendose a
     * disco cuando llega el siguiente disparo. Son unos MB y el recolector da
     * cuenta de ellos.
     */
    private var lastFullPhoto: Bitmap? = null

    /**
     * Carga el modelo por adelantado.
     *
     * Se llama al conceder el permiso, no al pulsar el disparador: el usuario
     * tarda unos segundos en encuadrar y esos segundos son gratis. Hacerlo al
     * disparar meteria la carga del modelo justo en el unico momento en que la
     * espera se nota.
     */
    fun prepare() {
        if (warmedUp || warmUpJob?.isActive == true) return
        val active = obtainClassifier() ?: return

        warmUpJob = viewModelScope.launch {
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
     * TOMA LA PROPIEDAD de [bitmap]: lo conserva hasta la siguiente foto o
     * hasta que muera la pantalla, por si el usuario elige un candidato y hay
     * que guardarlo junto a la observacion. A este tamano son unos 200 KB.
     *
     * @param fullPhoto el encuadre entero del que salio [bitmap], si lo hay.
     */
    fun classify(bitmap: Bitmap, context: ObservationContext, fullPhoto: Bitmap? = null) {
        // Un disparo a la vez. El boton se deshabilita mientras tanto, pero un
        // doble toque rapido llegaria igual hasta aqui.
        if (_uiState.value.isClassifying) {
            bitmap.recycle()
            return
        }

        val active = obtainClassifier()
        if (active == null) {
            bitmap.recycle()
            return
        }

        lastInput?.recycle()
        lastInput = bitmap
        lastContext = context
        lastFullPhoto = fullPhoto

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
                // Si la precarga sigue en marcha se espera en vez de lanzar otra:
                // dos warmUp simultaneos sobre el mismo interprete no estan
                // definidos y el segundo solo puede empeorar las cosas.
                warmUpJob?.join()

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
            } catch (e: IllegalStateException) {
                fail(CameraError.CAPTURE_FAILED, e)
            } catch (e: IllegalArgumentException) {
                fail(CameraError.CAPTURE_FAILED, e)
            } finally {
                _uiState.update { it.copy(isClassifying = false, isWarmingUp = false) }
            }
        }
    }

    /**
     * Clasifica una foto de la galeria.
     *
     * [display] se ensena hasta que se descarten los resultados, y [modelInput]
     * sigue el mismo camino (y la misma propiedad) que una foto de la camara.
     *
     * El contexto llega SIN ubicacion y con el prior apagado a proposito: una
     * foto de la galeria puede ser de cualquier sitio, y filtrarla con lo que
     * es plausible AQUI descartaria justo al ave que se trajo de un viaje.
     */
    fun classifyPicked(display: Bitmap, modelInput: Bitmap, context: ObservationContext) {
        if (_uiState.value.isClassifying) {
            display.recycle()
            modelInput.recycle()
            return
        }
        // La imagen que se ENSENA no se recicla a mano: Compose puede estar
        // pintando todavia el fotograma anterior, y dibujar un bitmap reciclado
        // revienta la app. Son unos pocos MB y el recolector da cuenta de ellos.
        _uiState.update { it.copy(pickedImage = display, hasResult = false) }
        classify(modelInput, context, fullPhoto = display)
    }

    /**
     * Empaqueta la eleccion del usuario con su foto y su contexto.
     *
     * La foto se COPIA: quien la recibe puede guardarla cuando quiera sin que
     * un disparo posterior la recicle debajo.
     */
    fun identification(candidate: ImageCandidate): ImageIdentification {
        val photo = lastInput?.takeIf { !it.isRecycled }?.let { source ->
            runCatching { source.copy(source.config ?: Bitmap.Config.ARGB_8888, false) }.getOrNull()
        }
        return ImageIdentification(
            candidate = candidate,
            modelId = _uiState.value.modelId ?: UNKNOWN_MODEL_ID,
            context = lastContext ?: ObservationContext(epochMillis = System.currentTimeMillis()),
            photo = photo,
            fullPhoto = lastFullPhoto?.takeIf { !it.isRecycled },
        )
    }

    /** La pantalla no pudo abrir o enlazar la camara. */
    fun reportCameraFailure(cause: Throwable) {
        fail(CameraError.CAMERA_UNAVAILABLE, cause)
    }

    /** El disparo fallo o la foto no se pudo decodificar. */
    fun reportCaptureFailure(cause: Throwable) {
        fail(CameraError.CAPTURE_FAILED, cause)
    }

    /** Vuelve al estado de encuadre, sin candidatos, error ni foto de galeria en pantalla. */
    fun dismissResults() {
        _uiState.update {
            it.copy(
                candidates = emptyList(),
                hasResult = false,
                error = null,
                errorDetail = null,
                pickedImage = null,
            )
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
                modelId = created.descriptor.modelId,
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
                rawConfidence = it.rawScore,
                labelIndex = it.labelIndex,
            )
        }

    override fun onCleared() {
        classifyJob?.cancel()
        classifyJob = null
        warmUpJob?.cancel()
        warmUpJob = null
        lastInput?.recycle()
        lastInput = null
        lastFullPhoto = null
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

        private const val UNKNOWN_MODEL_ID = "unknown-image-model"
    }
}
