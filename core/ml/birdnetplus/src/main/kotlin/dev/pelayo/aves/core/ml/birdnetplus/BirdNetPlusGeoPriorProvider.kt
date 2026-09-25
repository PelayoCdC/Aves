package dev.pelayo.aves.core.ml.birdnetplus

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import dev.pelayo.aves.core.ml.api.ContextPriorProvider
import dev.pelayo.aves.core.ml.api.ModelGate
import dev.pelayo.aves.core.ml.api.ObservationContext
import dev.pelayo.aves.core.ml.api.SpeciesPrior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.nio.FloatBuffer
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.round

/**
 * Prior geo-temporal del flavor `open`, con el geomodelo de BirdNET+ V3.
 *
 * Es el homologo del meta-modelo MData de BirdNET v2.4, y como el se prefiere
 * al prior de GBIF porque se entreno JUNTO al clasificador: sus probabilidades
 * de presencia estan pensadas para filtrar exactamente esas predicciones. Sus
 * pesos son Apache 2.0, como los del clasificador: viajan en el APK publicable.
 *
 * ENTRADA. El grafo exportado declara un solo tensor `x` y en su encoder hay
 * tres codificadores armonicos: latitud, longitud y semana. Se le pasa
 * `(latitud, longitud, semana)` con la misma convencion de 48 semanas que usa
 * toda la familia BirdNET. Si la convencion resultara ser otra, el prior se
 * desplazaria en el tiempo pero no rompería nada: el test instrumentado
 * comprueba que un petirrojo en Madrid en abril sale muy por encima de un
 * pinguino, que es lo que detectaria un desajuste grosero.
 *
 * SALIDA. Un tensor `sigmoid` con una probabilidad por clase, en el orden del
 * fichero de etiquetas del geomodelo (`birdnetplus_geo_labels.txt`, formato
 * `id<TAB>cientifico<TAB>comun`). Se cruza por nombre cientifico, que es de lo
 * que se deriva el speciesId en todo el proyecto.
 *
 * Igual que en el MData, se combina la semana actual con el maximo anual para
 * no penalizar a las migratorias adelantadas o rezagadas, y ningun multiplicador
 * vale cero.
 */
class BirdNetPlusGeoPriorProvider(
    private val context: Context,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ContextPriorProvider, Closeable {

    private val gate = ModelGate(::releaseModel)

    private var session: OrtSession? = null
    private var inputName: String = "x"
    private var outputName: String = "sigmoid"

    /** Indice de clase -> speciesId. Mismo orden que el tensor de salida. */
    private var speciesIds: List<String> = emptyList()

    private var cachedLatitude: Double? = null
    private var cachedLongitude: Double? = null
    private var cachedAnnualMax: FloatArray? = null
    private var cachedWeek: Int = 0
    private var cachedPrior: SpeciesPrior? = null

    suspend fun warmUp() {
        gate.use {
            if (session != null) return@use
            withContext(Dispatchers.IO) {
                speciesIds = context.assets.open(LABELS_ASSET).bufferedReader().use { reader ->
                    reader.lineSequence()
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .map { line ->
                            val columns = line.split('\t')
                            val scientific = columns.getOrElse(1) { columns[0] }.trim()
                            scientific.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                        }
                        .toList()
                }

                val env = OrtEnvironment.getEnvironment()
                val created = OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(1)
                    env.createSession(modelPath(), options)
                }
                inputName = created.inputNames.firstOrNull() ?: inputName
                outputName = created.outputNames.firstOrNull { it == outputName } ?: created.outputNames.first()
                session = created
                Log.i(TAG, "Geomodelo BirdNET+ listo: ${speciesIds.size} clases")
            }
        }
    }

    override suspend fun prior(context: ObservationContext): SpeciesPrior {
        val latitude = context.latitude
        val longitude = context.longitude
        if (latitude == null || longitude == null) return SpeciesPrior.Uniform
        if (context.contextInfluence <= 0f) return SpeciesPrior.Uniform

        val week = weekOfYear(context.epochMillis, zoneId)
        warmUp()

        return gate.use {
            val active = checkNotNull(session)
            withContext(Dispatchers.Default) { resolvePrior(active, latitude, longitude, week) }
        }
    }

    private fun resolvePrior(session: OrtSession, latitude: Double, longitude: Double, week: Int): SpeciesPrior {
        val latitudeKey = round(latitude * COORDINATE_SCALE) / COORDINATE_SCALE
        val longitudeKey = round(longitude * COORDINATE_SCALE) / COORDINATE_SCALE
        val sameLocation = latitudeKey == cachedLatitude && longitudeKey == cachedLongitude

        if (sameLocation && week == cachedWeek) {
            cachedPrior?.let { return it }
        }

        val annualMax = cachedAnnualMax.takeIf { sameLocation } ?: run {
            val maximum = FloatArray(speciesIds.size)
            for (w in 1..WEEKS_IN_YEAR) {
                val weekly = run(session, latitudeKey, longitudeKey, w)
                for (i in maximum.indices) if (weekly[i] > maximum[i]) maximum[i] = weekly[i]
            }
            Log.i(TAG, "Barrido anual del geomodelo para ($latitudeKey, $longitudeKey)")
            maximum
        }

        val weekly = run(session, latitudeKey, longitudeKey, week)
        val multipliers = HashMap<String, Float>(speciesIds.size)
        for (index in speciesIds.indices) {
            val presence = 0.5f * weekly[index] + 0.5f * annualMax[index]
            val multiplier = multiplierFor(presence)
            val previous = multipliers[speciesIds[index]]
            if (previous == null || multiplier > previous) multipliers[speciesIds[index]] = multiplier
        }
        val prior = object : SpeciesPrior {
            override fun get(speciesId: String): Float = multipliers[speciesId] ?: NEUTRAL_MULTIPLIER
        }

        cachedLatitude = latitudeKey
        cachedLongitude = longitudeKey
        cachedAnnualMax = annualMax
        cachedWeek = week
        cachedPrior = prior
        return prior
    }

    private fun run(session: OrtSession, latitude: Double, longitude: Double, week: Int): FloatArray {
        val env = OrtEnvironment.getEnvironment()
        val input = floatArrayOf(latitude.toFloat(), longitude.toFloat(), week.toFloat())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3)).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { results ->
                val raw = results.get(outputName).orElse(null)?.value
                    ?: error("El geomodelo no devolvio la salida '$outputName'")

                @Suppress("UNCHECKED_CAST")
                val predictions = (raw as Array<FloatArray>)[0]
                check(predictions.size == speciesIds.size) {
                    "El geomodelo devuelve ${predictions.size} clases y el fichero de etiquetas " +
                        "tiene ${speciesIds.size}. No corresponden."
                }
                return predictions
            }
        }
    }

    /**
     * Copia el modelo al almacenamiento sin copia de seguridad la primera vez,
     * igual que el clasificador. Ver `BirdNetPlusAudioClassifier.modelPath`.
     */
    private fun modelPath(): String =
        BirdNetPlusAssets.materialize(context, MODEL_ASSET)
            ?: throw FileNotFoundException(
                "Falta $MODEL_ASSET en los assets. Ejecuta scripts/fetch-models.ps1.",
            )

    override fun close() = gate.close()

    private fun releaseModel() {
        session?.close()
        session = null
        speciesIds = emptyList()
        cachedPrior = null
        cachedAnnualMax = null
        cachedLatitude = null
        cachedLongitude = null
    }

    companion object {
        private const val TAG = "BirdNetPlusGeo"

        const val MODEL_ASSET = "birdnetplus_geo.onnx"
        const val LABELS_ASSET = "birdnetplus_geo_labels.txt"

        /** El "ano" de la familia BirdNET tiene 48 semanas, no 52. */
        const val WEEKS_IN_YEAR = 48

        const val NEUTRAL_MULTIPLIER = 1f

        /** Nunca cero: con 0 una especie desaparece por construccion. */
        const val FLOOR_MULTIPLIER = 0.05f

        /** Clave de cache del barrido anual: 2 decimales, ~1 km. */
        private const val COORDINATE_SCALE = 100.0

        fun weekOfYear(epochMillis: Long, zoneId: ZoneId): Int {
            val dayOfYear = Instant.ofEpochMilli(epochMillis).atZone(zoneId).dayOfYear
            return ceil(dayOfYear * WEEKS_IN_YEAR.toDouble() / 366.0).toInt().coerceIn(1, WEEKS_IN_YEAR)
        }

        /**
         * Discretizacion de la probabilidad de presencia. La salida es una
         * sigmoide por clase, asi que los tramos se cortan en probabilidades
         * absolutas y no en las cuotas diminutas del MData. BirdNET-Analyzer
         * usa 0,03 como umbral por defecto para "esta especie esta aqui"; ese es
         * el tramo medio.
         */
        internal fun multiplierFor(presence: Float): Float = when {
            presence >= 0.10f -> 1f
            presence >= 0.03f -> 0.8f
            presence >= 0.01f -> 0.5f
            else -> FLOOR_MULTIPLIER
        }
    }
}

/**
 * Copia un asset ONNX a disco para que ONNX Runtime lo mapee en vez de cargar
 * decenas de MB en el monton de Java.
 *
 * Va a `noBackupFilesDir` y no a `filesDir`: la copia de seguridad automatica
 * de Android tiene una cuota de 25 MB, y un fichero de 67 MB dentro hacia que
 * fallara ENTERA, coleccion incluida. Y el nombre lleva el tamano del asset y
 * la fecha de instalacion del APK: asi un modelo actualizado en el APK
 * sustituye a la copia vieja, en vez de quedarse esta para siempre.
 */
internal object BirdNetPlusAssets {

    private const val DIRECTORY = "models"

    /**
     * Sincronizado: la escucha y el analisis de un fichero pueden arrancar a la
     * vez la primera vez, y compartian el temporal; la limpieza de uno borraba
     * el fichero que estaba escribiendo el otro.
     */
    @Synchronized
    fun materialize(context: Context, asset: String): String? {
        val available = context.assets.list("").orEmpty()
        if (asset !in available) return null

        // openFd solo funciona con assets sin comprimir, que es como se empaquetan
        // los .onnx (noCompress en el build).
        val declaredLength = context.assets.openFd(asset).use { it.declaredLength }
        val directory = File(context.noBackupFilesDir, DIRECTORY).apply { mkdirs() }
        val stem = asset.substringBeforeLast('.')
        val extension = asset.substringAfterLast('.', "")
        // El tamano solo no basta: unos pesos reentrenados con la misma
        // arquitectura pesan lo mismo byte a byte, y la copia vieja se quedaba.
        // La fecha de instalacion del APK cambia con cada actualizacion.
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        val target = File(directory, "$stem-$declaredLength-$installed.$extension")
        if (target.exists() && target.length() == declaredLength) return target.absolutePath

        // Copias de versiones anteriores, y la que dejaba la primera version en filesDir.
        directory.listFiles()?.filter { it.name.startsWith("$stem-") }?.forEach { it.delete() }
        File(context.filesDir, asset).delete()

        val temp = File(directory, "$stem-$declaredLength-$installed.tmp")
        context.assets.open(asset).use { source ->
            temp.outputStream().use { destination -> source.copyTo(destination) }
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            error("No se pudo dejar $asset en $directory")
        }
        return target.absolutePath
    }
}
