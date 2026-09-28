package dev.pelayo.aves.ml

import dev.pelayo.aves.core.audio.Pcm16Converter
import dev.pelayo.aves.core.audio.PcmScaling
import dev.pelayo.aves.core.audio.Resampler
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier
import dev.pelayo.aves.core.ml.api.ClassificationResult
import dev.pelayo.aves.core.ml.api.CombinedModel
import dev.pelayo.aves.core.ml.api.ModelDescriptor
import dev.pelayo.aves.core.ml.api.ObservationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Los dos modelos de cantos a la vez: cada ventana la escuchan BirdNET v2.4 y
 * BirdNET+, y de cada especie se queda la puntuacion mas alta de los dos.
 *
 * Es la opcion que mas detecta: cada modelo conoce especies que el otro no, y
 * donde coinciden, el que la oye mejor manda. Los falsos positivos de uno solo
 * los sigue frenando el estabilizador, que pide varias ventanas seguidas. El
 * precio es casi el doble de trabajo por ventana (se ejecutan en paralelo).
 *
 * **Formato.** v2.4 quiere 3 s a 48 kHz y BirdNET+ 3 s a 32 kHz. La escucha
 * captura en el formato del primero y aqui se remuestrea una copia para el
 * segundo, con el mismo paso-bajo que los ficheros.
 *
 * **Especies.** Los resultados se juntan por la especie CANONICA del catalogo,
 * no por la etiqueta de cada modelo: v2.4 aun dice *Sylvia communis* donde
 * BirdNET+ dice *Curruca communis*, y son la misma. El indice de clase se
 * codifica con [CombinedModel] para que el resto de la app sepa de que modelo
 * salio cada deteccion.
 *
 * @param resolve (modelo, indice) -> especie canonica, o null si no es un ave.
 */
class CombinedAudioClassifier(
    private val first: AudioBirdClassifier,
    private val second: AudioBirdClassifier,
    private val resolve: suspend (modelId: String, labelIndex: Int) -> String?,
    private val scaling: PcmScaling,
    private val topK: Int = DEFAULT_TOP_K,
) : AudioBirdClassifier {

    init {
        // Los dos tienen que mirar el MISMO tramo de audio.
        val firstMillis = first.requiredSampleCount * 1000L / first.requiredSampleRate
        val secondMillis = second.requiredSampleCount * 1000L / second.requiredSampleRate
        require(firstMillis == secondMillis) {
            "Las ventanas no duran lo mismo: $firstMillis ms y $secondMillis ms"
        }
    }

    override val descriptor = ModelDescriptor(
        modelId = CombinedModel.MODEL_ID,
        displayName = "${first.descriptor.displayName} + ${second.descriptor.displayName}",
        licenseSpdx = "${first.descriptor.licenseSpdx} AND ${second.descriptor.licenseSpdx}",
        allowsCommercialUse = first.descriptor.allowsCommercialUse && second.descriptor.allowsCommercialUse,
        attributionText = "${first.descriptor.attributionText}\n${second.descriptor.attributionText}",
        speciesCount = maxOf(first.descriptor.speciesCount, second.descriptor.speciesCount),
    )

    override val requiredSampleRate = first.requiredSampleRate
    override val requiredSampleCount = first.requiredSampleCount

    override suspend fun warmUp() = coroutineScope {
        val a = async { first.warmUp() }
        val b = async { second.warmUp() }
        a.await()
        b.await()
    }

    override suspend fun classify(samples: FloatArray, context: ObservationContext): List<ClassificationResult> =
        coroutineScope {
            val fromFirst = async { first.classify(samples, context) }
            val fromSecond = async { second.classify(forSecond(samples), context) }
            mergeByCanonicalSpecies(
                first = fromFirst.await(),
                second = fromSecond.await(),
                firstModelId = first.descriptor.modelId,
                secondModelId = second.descriptor.modelId,
                resolve = resolve,
                topK = topK,
            )
        }

    /** La misma ventana en el formato del segundo modelo. */
    private fun forSecond(samples: FloatArray): FloatArray {
        if (second.requiredSampleRate == first.requiredSampleRate) return samples
        val pcm = Pcm16Converter.toPcm16(samples, scaling)
        val resampled = Resampler.resample(pcm, first.requiredSampleRate, second.requiredSampleRate)
        // El redondeo puede dejar una muestra de mas o de menos: se ajusta.
        return FloatArray(second.requiredSampleCount) { index ->
            if (index < resampled.size) scaling.apply(resampled[index]) else 0f
        }
    }

    override fun close() {
        first.close()
        second.close()
    }

    companion object {
        /** El mismo que cada modelo por separado: lo que se ensena lo decide el estabilizador. */
        const val DEFAULT_TOP_K = 8
    }
}

/**
 * Junta los candidatos de los dos modelos por especie canonica y se queda, de
 * cada una, con el de mas puntuacion. El resultado sale con el speciesId
 * canonico y el indice codificado de quien gano ([CombinedModel]).
 *
 * Lo que no es un ave para su modelo (no resuelve) se descarta aqui.
 */
internal suspend fun mergeByCanonicalSpecies(
    first: List<ClassificationResult>,
    second: List<ClassificationResult>,
    firstModelId: String,
    secondModelId: String,
    resolve: suspend (modelId: String, labelIndex: Int) -> String?,
    topK: Int,
): List<ClassificationResult> {
    val best = LinkedHashMap<String, ClassificationResult>()

    suspend fun offer(result: ClassificationResult, modelId: String, encoded: Int) {
        val canonical = resolve(modelId, result.labelIndex) ?: return
        val current = best[canonical]
        if (current == null || result.adjustedScore > current.adjustedScore) {
            best[canonical] = result.copy(speciesId = canonical, labelIndex = encoded)
        }
    }

    for (result in first) offer(result, firstModelId, CombinedModel.encodeFirst(result.labelIndex))
    for (result in second) offer(result, secondModelId, CombinedModel.encodeSecond(result.labelIndex))

    return best.values.sortedByDescending { it.adjustedScore }.take(topK)
}
