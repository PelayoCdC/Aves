package dev.pelayo.aves.core.ml.birdnetplus

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier
import dev.pelayo.aves.core.ml.api.ClassificationResult
import dev.pelayo.aves.core.ml.api.ContextPriorProvider
import dev.pelayo.aves.core.ml.api.ModelDescriptor
import dev.pelayo.aves.core.ml.api.ModelGate
import dev.pelayo.aves.core.ml.api.ObservationContext
import dev.pelayo.aves.core.ml.api.SpeciesPrior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.nio.FloatBuffer

/**
 * Clasificador de cantos basado en BirdNET+ V3.
 *
 * **Este es el modulo que hace publicable la app.** Sus pesos son Apache 2.0,
 * no CC BY-NC-SA como los de BirdNET v2.4: el propio equipo de BirdNET los
 * distribuye asi en `birdnet-team/birdnet-live-app`, cuyo `MODEL_LICENSE` dice
 * literalmente que los pesos empaquetados estan bajo Apache 2.0.
 *
 * Ademas cubre mas: 8.923 especies de ave frente a las 6.421 clases de ave de
 * la v2.4. El flavor `open` acaba siendo mejor que el `personal`.
 *
 * DIFERENCIAS QUE IMPORTAN RESPECTO A LA v2.4
 * - **32 kHz, no 48 kHz.** Ventana de 3 s = 96.000 muestras. Este es
 *   exactamente el caso para el que se diseño que el modelo declare su propio
 *   formato: `:core:audio` se adapta sin tocar una linea.
 * - **ONNX, no TFLite.** De ahi el runtime distinto.
 * - El fichero de etiquetas trae la clase taxonomica, asi que separar aves de
 *   ranas y grillos es directo en vez de deducirlo del codigo.
 */
class BirdNetPlusAudioClassifier(
    private val context: Context,
    private val priorProvider: ContextPriorProvider? = null,
    private val threadCount: Int = DEFAULT_THREAD_COUNT,
    private val topK: Int = DEFAULT_TOP_K,
    private val minScore: Float = DEFAULT_MIN_SCORE,
) : AudioBirdClassifier {

    override val descriptor = ModelDescriptor(
        modelId = MODEL_ID,
        displayName = "BirdNET+ V3.0 Global 10K",
        licenseSpdx = "Apache-2.0",
        allowsCommercialUse = true,
        attributionText = "Identificacion por canto: BirdNET+ (K. Lisa Yang Center for " +
            "Conservation Bioacoustics, Cornell Lab of Ornithology). Pesos bajo " +
            "licencia Apache 2.0.",
        speciesCount = BirdNetPlusLabels.BIRD_COUNT,
    )

    override val requiredSampleRate = SAMPLE_RATE
    override val requiredSampleCount = SAMPLE_COUNT

    // Una inferencia a la vez, y la sesion no se cierra con una a medias.
    private val gate = ModelGate(::releaseModel)

    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var labels: List<BirdNetPlusLabel> = emptyList()
    private var inputName: String = DEFAULT_INPUT_NAME
    private var outputName: String = DEFAULT_OUTPUT_NAME

    private val scores = FloatArray(BirdNetPlusLabels.EXPECTED_COUNT)

    override suspend fun warmUp() {
        gate.use {
            if (session != null) return@use
            withContext(Dispatchers.IO) {
                labels = context.assets.open(LABELS_ASSET).bufferedReader().use { reader ->
                    BirdNetPlusLabels.parse(reader.lineSequence())
                }

                val env = OrtEnvironment.getEnvironment()
                // Las opciones reservan memoria nativa: se cierran en cuanto la
                // sesion esta creada, que ya no las necesita.
                val created = OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(threadCount)
                    env.createSession(modelPath(), options)
                }

                // Los nombres de entrada y salida se leen de la sesion en vez de
                // fijarlos: si el modelo se actualiza y los renombra, esto falla
                // al cargar con un mensaje claro en lugar de en la inferencia
                // con un NullPointerException.
                inputName = created.inputNames.firstOrNull() ?: DEFAULT_INPUT_NAME
                outputName = created.outputNames.firstOrNull { it == DEFAULT_OUTPUT_NAME }
                    ?: created.outputNames.first()

                environment = env
                session = created
                Log.i(
                    TAG,
                    "BirdNET+ listo: ${labels.size} taxones " +
                        "(${labels.count { it.isBird }} aves), entrada '$inputName', " +
                        "salida '$outputName', $threadCount hilos",
                )
            }
        }
    }

    override suspend fun classify(
        samples: FloatArray,
        context: ObservationContext,
    ): List<ClassificationResult> {
        require(samples.size == SAMPLE_COUNT) {
            "BirdNET+ necesita exactamente $SAMPLE_COUNT muestras a $SAMPLE_RATE Hz " +
                "y recibio ${samples.size}"
        }
        warmUp()

        val prior = resolvePrior(context)

        return gate.use {
            val active = checkNotNull(session)
            val env = checkNotNull(environment)
            withContext(Dispatchers.Default) {
                runInference(env, active, samples)
                buildResults(prior, context.contextInfluence)
            }
        }
    }

    private suspend fun resolvePrior(context: ObservationContext): SpeciesPrior =
        if (priorProvider == null || !context.hasLocation || context.contextInfluence <= 0f) {
            SpeciesPrior.Uniform
        } else {
            priorProvider.prior(context)
        }

    private fun runInference(env: OrtEnvironment, session: OrtSession, samples: FloatArray) {
        // El tensor se construye por inferencia y se cierra siempre: ONNX Runtime
        // reserva memoria nativa que el recolector de Java no ve.
        OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(samples),
            longArrayOf(1, samples.size.toLong()),
        ).use { input ->
            session.run(mapOf(inputName to input)).use { results ->
                val raw = results.get(outputName).orElse(null)?.value
                    ?: error("El modelo no devolvio la salida '$outputName'")

                @Suppress("UNCHECKED_CAST")
                val predictions = (raw as Array<FloatArray>)[0]
                check(predictions.size == scores.size) {
                    "El modelo devuelve ${predictions.size} clases y el fichero de " +
                        "etiquetas tiene ${scores.size}. No corresponden."
                }
                predictions.copyInto(scores)
            }
        }
    }

    private fun buildResults(prior: SpeciesPrior, influence: Float): List<ClassificationResult> {
        val weight = influence.coerceIn(0f, 1f)
        val candidates = ArrayList<ClassificationResult>(topK + 1)

        for (index in scores.indices) {
            val label = labels[index]
            // El modelo incluye anfibios, mamiferos e insectos a proposito.
            // Esta app se llama Aves.
            if (!label.isBird) continue

            val raw = scores[index]
            if (raw < minScore) continue

            val priorValue = prior[label.speciesId]
            candidates += ClassificationResult(
                speciesId = label.speciesId,
                rawLabel = "${label.scientificName}_${label.commonName}",
                labelIndex = index,
                rawScore = raw,
                adjustedScore = raw * ((1f - weight) + weight * priorValue),
            )
        }

        return candidates.sortedByDescending { it.adjustedScore }.take(topK)
    }

    /**
     * Copia el modelo a disco la primera vez y devuelve su ruta. Ver
     * [BirdNetPlusAssets]: sin copia de seguridad y con el tamano en el nombre.
     */
    private fun modelPath(): String =
        BirdNetPlusAssets.materialize(context, MODEL_ASSET)
            ?: throw FileNotFoundException(
                "Falta $MODEL_ASSET en los assets. Ejecuta scripts/fetch-models.ps1: " +
                    "descarga los pesos Apache 2.0 desde birdnet-team/birdnet-live-app.",
            )

    override fun close() = gate.close()

    private fun releaseModel() {
        session?.close()
        session = null
        // El OrtEnvironment es un singleton del proceso: cerrarlo dejaria sin
        // runtime a cualquier otra sesion viva.
        environment = null
        labels = emptyList()
    }

    companion object {
        private const val TAG = "BirdNetPlusClassifier"

        const val MODEL_ID = "birdnetplus-v3.0"

        /** 32 kHz, no 48: BirdNET+ V3 cambio de frecuencia respecto a la v2.4. */
        const val SAMPLE_RATE = 32_000

        /** Ventana por defecto de 3 s. El modelo admite tambien 5 y 10. */
        const val SAMPLE_COUNT = 96_000

        const val MODEL_ASSET = "birdnetplus_audio.onnx"
        const val LABELS_ASSET = "birdnetplus_labels.csv"

        private const val DEFAULT_INPUT_NAME = "input"
        private const val DEFAULT_OUTPUT_NAME = "predictions"

        private const val DEFAULT_THREAD_COUNT = 2
        /**
         * Ocho y no tres: el estabilizador necesita ver a una especie en ventanas
         * CONSECUTIVAS para confirmarla. Con dos aves fuertes cantando, la
         * tercera se caia del top-3 y perdia la racha aunque siguiera ahi. Lo
         * que se ensena al usuario lo decide la histeresis, no este numero.
         */
        private const val DEFAULT_TOP_K = 8
        private const val DEFAULT_MIN_SCORE = 0.05f
    }
}
