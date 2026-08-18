package dev.pelayo.aves.core.ml.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dev.pelayo.aves.core.ml.api.ClassificationResult
import dev.pelayo.aves.core.ml.api.ContextPriorProvider
import dev.pelayo.aves.core.ml.api.ImageBirdClassifier
import dev.pelayo.aves.core.ml.api.ModelDescriptor
import dev.pelayo.aves.core.ml.api.ObservationContext
import dev.pelayo.aves.core.ml.api.SpeciesPrior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Clasificador de aves por imagen, basado en Google AIY birds_V1.
 *
 * A diferencia de BirdNET, este modelo **es Apache 2.0**, asi que entra en los
 * dos flavors, incluido el publicable. Y es diminuto: 3,4 MB frente a los 50 MB
 * del de audio.
 *
 * USA LA VERSION 3 Y SOLO LA 3. El changelog dice que corrige un
 * "out-of-order logit to labelmap mapping": las versiones 1 y 2 devuelven la
 * especie equivocada sin dar ningun sintoma.
 *
 * La foto es la via secundaria de identificacion. Se usa cuando ves el ave pero
 * no la oyes; el canto sigue siendo el camino principal porque los pajaros se
 * oyen mucho mas de lo que se dejan fotografiar.
 */
class AiyBirdImageClassifier(
    private val context: Context,
    private val priorProvider: ContextPriorProvider? = null,
    private val threadCount: Int = DEFAULT_THREAD_COUNT,
    private val topK: Int = DEFAULT_TOP_K,
    private val minScore: Float = DEFAULT_MIN_SCORE,
) : ImageBirdClassifier {

    override val descriptor = ModelDescriptor(
        modelId = MODEL_ID,
        displayName = "Google AIY Vision Classifier birds_V1",
        licenseSpdx = "Apache-2.0",
        allowsCommercialUse = true,
        attributionText = "Identificacion por imagen: Google AIY Vision Classifier " +
            "birds_V1, licencia Apache 2.0.",
        speciesCount = AiyBirdLabels.EXPECTED_COUNT - 1,
    )

    override val inputSize = INPUT_SIZE

    private val mutex = Mutex()
    private var interpreter: Interpreter? = null
    private var labels: List<AiyLabel> = emptyList()

    /**
     * Si el modelo espera enteros de 8 bits o flotantes.
     *
     * Se descubre leyendo el tensor en vez de asumirlo. Google publica variantes
     * cuantizadas y en coma flotante del mismo modelo, y alimentar la equivocada
     * no produce ningun error: solo predicciones basura con aspecto normal.
     */
    private var quantized = false

    private var inputBuffer: ByteBuffer? = null
    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    private var scores = FloatArray(AiyBirdLabels.EXPECTED_COUNT)

    override suspend fun warmUp() {
        mutex.withLock {
            if (interpreter != null) return@withLock
            withContext(Dispatchers.IO) {
                labels = context.assets.open(LABELS_ASSET).bufferedReader().use { reader ->
                    AiyBirdLabels.parse(reader.lineSequence())
                }

                val options = Interpreter.Options().apply { numThreads = threadCount }
                val created = Interpreter(loadModel(), options)

                val inputTensor = created.getInputTensor(0)
                quantized = inputTensor.dataType() == DataType.UINT8
                val bytesPerChannel = if (quantized) 1 else Float.SIZE_BYTES
                inputBuffer = ByteBuffer
                    .allocateDirect(INPUT_SIZE * INPUT_SIZE * CHANNELS * bytesPerChannel)
                    .order(ByteOrder.nativeOrder())

                val outputCount = created.getOutputTensor(0).shape().last()
                check(outputCount == AiyBirdLabels.EXPECTED_COUNT) {
                    "El modelo devuelve $outputCount clases y el labelmap tiene " +
                        "${AiyBirdLabels.EXPECTED_COUNT}. No corresponden."
                }
                scores = FloatArray(outputCount)

                interpreter = created
                Log.i(
                    TAG,
                    "Clasificador de imagen listo: ${labels.size} clases, " +
                        (if (quantized) "entrada uint8" else "entrada float32"),
                )
            }
        }
    }

    override suspend fun classify(
        bitmap: Bitmap,
        context: ObservationContext,
    ): List<ClassificationResult> {
        warmUp()
        val prior = resolvePrior(context)

        return mutex.withLock {
            val active = checkNotNull(interpreter) { "El clasificador ya se ha cerrado" }
            withContext(Dispatchers.Default) {
                writeInput(bitmap)
                val output = Array(1) { FloatArray(scores.size) }
                active.run(inputBuffer, output)
                output[0].copyInto(scores)
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

    /**
     * Convierte el bitmap al tensor de entrada.
     *
     * El bitmap tiene que llegar ya recortado y escalado a [INPUT_SIZE]: quien
     * captura la imagen sabe recortar al centro sin deformar, y hacerlo aqui
     * obligaria a este modulo a conocer decisiones de encuadre que no le
     * corresponden.
     */
    private fun writeInput(bitmap: Bitmap) {
        require(bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
            "El modelo espera ${INPUT_SIZE}x$INPUT_SIZE y recibio " +
                "${bitmap.width}x${bitmap.height}"
        }
        val buffer = checkNotNull(inputBuffer)
        buffer.rewind()
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (quantized) {
                buffer.put(r.toByte())
                buffer.put(g.toByte())
                buffer.put(b.toByte())
            } else {
                // El model card especifica entrada escalada a [0,1].
                buffer.putFloat(r / 255f)
                buffer.putFloat(g / 255f)
                buffer.putFloat(b / 255f)
            }
        }
        buffer.rewind()
    }

    private fun buildResults(prior: SpeciesPrior, influence: Float): List<ClassificationResult> {
        val weight = influence.coerceIn(0f, 1f)
        val candidates = ArrayList<ClassificationResult>(topK + 1)

        for (index in scores.indices) {
            val label = labels[index]
            // `background` significa "esto no es un ave". Es informacion util
            // para el modelo, pero no debe llegar a la coleccion.
            if (label.isBackground) continue

            val raw = scores[index]
            if (raw < minScore) continue

            val priorValue = prior[label.provisionalSpeciesId]
            candidates += ClassificationResult(
                speciesId = label.provisionalSpeciesId,
                rawLabel = label.scientificName,
                labelIndex = index,
                rawScore = raw,
                adjustedScore = raw * ((1f - weight) + weight * priorValue),
            )
        }

        return candidates.sortedByDescending { it.adjustedScore }.take(topK)
    }

    private fun loadModel(): MappedByteBuffer {
        val available = context.assets.list("").orEmpty().toSet()
        val asset = MODEL_ASSET_CANDIDATES.firstOrNull { it in available }
            ?: throw FileNotFoundException(
                "No hay modelo de imagen en assets. Ejecuta scripts/fetch-models.ps1 " +
                    "(necesita cuenta de Kaggle). Se buscaron: " +
                    MODEL_ASSET_CANDIDATES.joinToString(),
            )

        return context.assets.openFd(asset).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength,
                )
            }
        }
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
        labels = emptyList()
        inputBuffer = null
    }

    companion object {
        private const val TAG = "AiyImageClassifier"

        const val MODEL_ID = "aiy-birds-v1"

        const val INPUT_SIZE = 224
        private const val CHANNELS = 3

        /**
         * Nombres que puede tener el fichero descargado de Kaggle segun la
         * variante. Se busca en vez de fijar uno para no obligar a renombrar.
         */
        val MODEL_ASSET_CANDIDATES = listOf(
            "aiy_birds_V1.tflite",
            "lite-model_aiy_vision_classifier_birds_V1_3.tflite",
            "model.tflite",
        )

        const val LABELS_ASSET = "aiy_birds_labelmap.csv"

        private const val DEFAULT_THREAD_COUNT = 2
        private const val DEFAULT_TOP_K = 3

        /**
         * Mas alto que en audio (0.05) a proposito: una foto es un evento
         * puntual y deliberado, no un flujo continuo del que se pueda acumular
         * evidencia. Sin histeresis que respalde, conviene ser mas exigente.
         */
        private const val DEFAULT_MIN_SCORE = 0.1f
    }
}
