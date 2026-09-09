package dev.pelayo.aves.core.ml.birdnet

import android.content.Context
import android.util.Log
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier
import dev.pelayo.aves.core.ml.api.ClassificationResult
import dev.pelayo.aves.core.ml.api.ContextPriorProvider
import dev.pelayo.aves.core.ml.api.ModelDescriptor
import dev.pelayo.aves.core.ml.api.ObservationContext
import dev.pelayo.aves.core.ml.api.SpeciesPrior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * Clasificador de cantos basado en BirdNET GLOBAL 6K v2.4.
 *
 * Detalle que ahorra la mitad del trabajo esperado: **el .tflite acepta PCM
 * crudo**, `float32[1, 144000]`. El espectrograma mel esta dentro del grafo, asi
 * que no hay que implementar FFT ni bajar a JNI. La salida son LOGITS, no
 * probabilidades, y hay que pasarlos por sigmoide (no softmax: una grabacion
 * puede contener varias especies a la vez, es multi-etiqueta).
 *
 * @param priorProvider filtro geo-temporal. En Fase 1 es null y no se aplica
 *   ningun prior; en Fase 4 entra el meta-modelo MData.
 */
class BirdNetAudioClassifier(
    private val context: Context,
    private val priorProvider: ContextPriorProvider? = null,
    private val threadCount: Int = DEFAULT_THREAD_COUNT,
    private val topK: Int = DEFAULT_TOP_K,
    private val minScore: Float = DEFAULT_MIN_SCORE,
) : AudioBirdClassifier {

    override val descriptor = ModelDescriptor(
        modelId = MODEL_ID,
        displayName = "BirdNET GLOBAL 6K v2.4",
        licenseSpdx = "CC-BY-NC-SA-4.0",
        allowsCommercialUse = false,
        attributionText = "Identificacion por canto: BirdNET (Stefan Kahl, Connor Wood, " +
            "Maximilian Eibl, Holger Klinck), K. Lisa Yang Center for Conservation " +
            "Bioacoustics, Cornell Lab of Ornithology. Licencia CC BY-NC-SA 4.0.",
        speciesCount = BirdNetLabels.BIRD_SPECIES_COUNT,
    )

    override val requiredSampleRate = SAMPLE_RATE
    override val requiredSampleCount = SAMPLE_COUNT

    // El interprete no es thread-safe y ademas se reutilizan los buffers, asi
    // que una sola inferencia a la vez.
    private val mutex = Mutex()

    private var interpreter: Interpreter? = null
    private var labels: List<BirdNetLabel> = emptyList()

    // Buffers directos reservados una vez: con una inferencia cada 800 ms,
    // reasignar 576 KB cada vez seria una fuente de presion de GC innecesaria.
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(SAMPLE_COUNT * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    private val outputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(BirdNetLabels.EXPECTED_COUNT * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
    private val logits = FloatArray(BirdNetLabels.EXPECTED_COUNT)

    override suspend fun warmUp() {
        mutex.withLock {
            if (interpreter != null) return@withLock
            withContext(Dispatchers.IO) {
                labels = context.assets.open(LABELS_ASSET).bufferedReader().use { labelReader ->
                    context.assets.open(TAXO_CODES_ASSET).bufferedReader().use { codeReader ->
                        BirdNetLabels.parse(
                            labelLines = labelReader.lineSequence(),
                            // Los codigos taxonomicos permiten distinguir las ~90
                            // clases que son ranas, grillos o sapos: BirdNET las
                            // incluye, pero esta app se llama Aves.
                            taxoCodeLines = codeReader.readText().lineSequence(),
                        )
                    }
                }

                val options = Interpreter.Options().apply { numThreads = threadCount }
                val created = Interpreter(loadModel(), options)

                // Una inferencia en vacio para que la primera de verdad no pague
                // la inicializacion perezosa del runtime (facilmente 300-500 ms).
                runInference(created, FloatArray(SAMPLE_COUNT))
                interpreter = created
                Log.i(TAG, "BirdNET listo: ${labels.size} etiquetas, $threadCount hilos")
            }
        }
    }

    override suspend fun classify(
        samples: FloatArray,
        context: ObservationContext,
    ): List<ClassificationResult> {
        require(samples.size == SAMPLE_COUNT) {
            "BirdNET necesita exactamente $SAMPLE_COUNT muestras y recibio ${samples.size}"
        }
        warmUp()

        val prior = resolvePrior(context)

        return mutex.withLock {
            val active = checkNotNull(interpreter) { "El clasificador ya se ha cerrado" }
            withContext(Dispatchers.Default) {
                runInference(active, samples)
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

    private fun runInference(interpreter: Interpreter, samples: FloatArray) {
        inputBuffer.rewind()
        inputBuffer.asFloatBuffer().put(samples)
        inputBuffer.rewind()
        outputBuffer.rewind()

        interpreter.run(inputBuffer, outputBuffer)

        outputBuffer.rewind()
        outputBuffer.asFloatBuffer().get(logits)
    }

    private fun buildResults(prior: SpeciesPrior, influence: Float): List<ClassificationResult> {
        val weight = influence.coerceIn(0f, 1f)
        val candidates = ArrayList<ClassificationResult>(topK + 1)

        for (index in logits.indices) {
            val label = labels[index]
            if (!label.isBird) continue

            val raw = sigmoid(logits[index])
            if (raw < minScore) continue

            // Mezcla suave en vez de multiplicar por el prior a secas: con
            // weight=0 el contexto no influye nada, con weight=1 influye del
            // todo, y nunca se anula por completo un candidato.
            val priorValue = prior[label.provisionalSpeciesId]
            val adjusted = raw * ((1f - weight) + weight * priorValue)

            candidates += ClassificationResult(
                speciesId = label.provisionalSpeciesId,
                rawLabel = "${label.scientificName}_${label.commonName}",
                labelIndex = index,
                rawScore = raw,
                adjustedScore = adjusted,
            )
        }

        return candidates
            .sortedByDescending { it.adjustedScore }
            .take(topK)
    }

    /**
     * Localiza el modelo entre las variantes admitidas.
     *
     * Se busca en vez de fijar un nombre porque FP32 y FP16 son intercambiables
     * y el script de descarga permite elegir. Si no hay ninguno, el mensaje debe
     * decir exactamente que hacer: es el fallo mas probable en un clon recien
     * hecho del repositorio.
     */
    private fun resolveModelAsset(): String {
        val available = context.assets.list("").orEmpty().toSet()
        return MODEL_ASSET_CANDIDATES.firstOrNull { it in available }
            ?: throw java.io.FileNotFoundException(
                "No hay ningun modelo de BirdNET en assets. Ejecuta scripts/fetch-models.ps1 " +
                    "(se buscaron: ${MODEL_ASSET_CANDIDATES.joinToString()})",
            )
    }

    private fun loadModel(): MappedByteBuffer =
        context.assets.openFd(resolveModelAsset()).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength,
                )
            }
        }

    /**
     * Sigmoide, no softmax. El modelo es multi-etiqueta: en una grabacion puede
     * haber dos aves cantando y ambas deben poder salir con confianza alta.
     */
    private fun sigmoid(logit: Float): Float = 1f / (1f + exp(-logit))

    /**
     * Imprescindible llamarlo: el interprete retiene un MappedByteBuffer de
     * ~25 MB que el recolector de basura no libera solo.
     */
    override fun close() {
        interpreter?.close()
        interpreter = null
        labels = emptyList()
    }

    companion object {
        private const val TAG = "BirdNetClassifier"

        const val MODEL_ID = "birdnet-v2.4"

        /** Formato exigido por el grafo: 3 segundos a 48 kHz mono. */
        const val SAMPLE_RATE = 48_000
        const val SAMPLE_COUNT = 144_000

        /**
         * En orden de preferencia. FP16 primero: es la mitad de tamano (25 MB
         * frente a 51) y en un movil no se nota la diferencia de precision. El
         * FP32 solo tiene sentido para comparar contra las salidas de
         * referencia de BirdNET-Analyzer, y el script de descarga no lo baja
         * salvo que se pida. Tener los DOS en assets duplicaba el APK.
         */
        val MODEL_ASSET_CANDIDATES = listOf(
            "BirdNET_GLOBAL_6K_V2.4_Model_FP16.tflite",
            "BirdNET_GLOBAL_6K_V2.4_Model_FP32.tflite",
        )

        const val LABELS_ASSET = "labels_es.txt"
        const val TAXO_CODES_ASSET = "taxo_code.txt"

        private const val DEFAULT_THREAD_COUNT = 2

        /**
         * Ocho y no tres: el estabilizador necesita ver a una especie en ventanas
         * CONSECUTIVAS para confirmarla. Con dos aves fuertes cantando, la
         * tercera se caia del top-3 y perdia la racha aunque siguiera ahi. Lo
         * que se ensena al usuario lo decide la histeresis, no este numero.
         */
        private const val DEFAULT_TOP_K = 8

        /**
         * Suelo bajo a proposito: aqui solo se recortan candidatos irrelevantes.
         * El umbral que decide si algo se ENSENA al usuario (0,35 de fabrica,
         * ajustable) vive en el estabilizador de `:core:listening`, porque es
         * una decision de producto y no del modelo.
         */
        private const val DEFAULT_MIN_SCORE = 0.05f
    }
}
