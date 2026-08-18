package dev.pelayo.aves.core.ml.birdnet

import android.content.Context
import android.util.Log
import dev.pelayo.aves.core.ml.api.ContextPriorProvider
import dev.pelayo.aves.core.ml.api.ObservationContext
import dev.pelayo.aves.core.ml.api.SpeciesPrior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.round

/**
 * Prior geo-temporal basado en el meta-modelo MData V2 de BirdNET.
 *
 * El clasificador de audio distingue 6.522 clases, pero en un punto concreto del
 * planeta y una fecha concreta solo unas cientos son plausibles. Este modelo
 * diminuto (14 MB) recibe `float32[1, 3]` = `(latitud, longitud, weekMeta)` y
 * devuelve `float32[1, 6522]` con la probabilidad de presencia de cada clase, en
 * el MISMO orden que el tensor de salida del clasificador.
 *
 * ## Ningun prior vale exactamente 0
 *
 * Es una decision deliberada, no un descuido: el tramo mas bajo devuelve
 * [FLOOR_MULTIPLIER] (0.05) y nunca cero. Un cero elimina una especie POR
 * CONSTRUCCION, haga lo que haga el audio, y las primeras victimas serian las
 * rarezas y los divagantes, que son justo lo mas interesante que puede pasar en
 * esta app. Con 0.05 una rareza necesita una senal muy clara para salir, pero
 * sigue siendo alcanzable. Lo mismo vale para las especies que el prior no
 * conoce: devuelven el multiplicador neutro (1.0), porque desconocer una especie
 * no es motivo para penalizarla.
 *
 * ## Truco para las migratorias
 *
 * Usar solo la probabilidad de la semana actual descarta a las aves que llegan
 * antes de tiempo o se quedan mas de la cuenta, que es precisamente cuando mas
 * ilusion hace detectarlas. Por eso se combina 50/50 la semana actual con el
 * maximo de las 48 semanas del ano (ver [BirdNetPriorMath.combineWithAnnualMax]):
 * una especie que aqui aparece en algun momento del ano nunca cae al suelo.
 *
 * El barrido anual son 48 inferencias de un modelo minusculo y su resultado solo
 * depende de la ubicacion, asi que se cachea por coordenadas redondeadas y se
 * reaprovecha entre grabaciones y entre semanas.
 *
 * @param zoneId zona horaria con la que se traduce el instante a dia del ano. Se
 *   inyecta para poder fijarla en los tests.
 */
class BirdNetContextPriorProvider(
    private val context: Context,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val threadCount: Int = DEFAULT_THREAD_COUNT,
) : ContextPriorProvider, Closeable {

    // El interprete no es thread-safe y ademas se comparten los buffers y la
    // cache, asi que una sola inferencia a la vez.
    private val mutex = Mutex()

    private var interpreter: Interpreter? = null

    /** Indice de clase -> speciesId provisional. Mismo orden que el tensor. */
    private var speciesIds: List<String> = emptyList()

    // Buffers directos reservados una vez. El de entrada es ridiculo (12 bytes),
    // pero el de salida son 26 KB por inferencia y el barrido anual hace 48
    // seguidas: reasignarlos seria presion de GC gratuita.
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(META_INPUT_SIZE * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    private val outputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(BirdNetLabels.EXPECTED_COUNT * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
    private val metaOutput = FloatArray(BirdNetLabels.EXPECTED_COUNT)

    // Cache. Solo se guarda la ultima ubicacion: un usuario esta en un sitio, y
    // guardar mas historico no compensa el megabyte que costaria.
    private var cachedLatitude: Double? = null
    private var cachedLongitude: Double? = null
    private var cachedAnnualMax: FloatArray? = null
    private var cachedWeek: Int = 0
    private var cachedPrior: SpeciesPrior? = null

    suspend fun warmUp() {
        mutex.withLock {
            if (interpreter != null) return@withLock
            withContext(Dispatchers.IO) {
                // Aqui no hacen falta los codigos taxonomicos: el prior se
                // consulta por speciesId y quien filtra las ranas y los ruidos es
                // el clasificador, que es el unico que llega a la interfaz.
                speciesIds = context.assets.open(BirdNetAudioClassifier.LABELS_ASSET)
                    .bufferedReader()
                    .use { reader ->
                        BirdNetLabels.parse(reader.lineSequence())
                            .map(BirdNetLabel::provisionalSpeciesId)
                    }

                val options = Interpreter.Options().apply { numThreads = threadCount }
                val created = Interpreter(loadModel(), options)

                // Inferencia en vacio para pagar aqui la inicializacion perezosa
                // del runtime y no dentro del primer barrido anual.
                runMeta(created, 0.0, 0.0, week = 1)
                interpreter = created
                Log.i(TAG, "Meta-modelo listo: ${speciesIds.size} clases, $threadCount hilos")
            }
        }
    }

    /**
     * Sin ubicacion no hay nada que decir: se devuelve [SpeciesPrior.Uniform], que
     * deja la salida del clasificador intacta. Denegar el permiso de ubicacion
     * degrada la precision, no rompe la deteccion.
     */
    override suspend fun prior(context: ObservationContext): SpeciesPrior {
        val latitude = context.latitude
        val longitude = context.longitude
        if (latitude == null || longitude == null) return SpeciesPrior.Uniform
        // Si el contexto no pesa nada, el resultado seria identico al neutro y no
        // vale la pena despertar el interprete.
        if (context.contextInfluence <= 0f) return SpeciesPrior.Uniform

        val week = BirdNetPriorMath.weekOfYear(context.epochMillis, zoneId)
        // Fuera del mutex: warmUp lo toma por su cuenta y Mutex no es reentrante.
        warmUp()

        return mutex.withLock {
            val active = checkNotNull(interpreter) { "El proveedor de priors ya se ha cerrado" }
            withContext(Dispatchers.Default) { resolvePrior(active, latitude, longitude, week) }
        }
    }

    private fun resolvePrior(
        interpreter: Interpreter,
        latitude: Double,
        longitude: Double,
        week: Int,
    ): SpeciesPrior {
        val latitudeKey = BirdNetPriorMath.roundCoordinate(latitude)
        val longitudeKey = BirdNetPriorMath.roundCoordinate(longitude)
        val sameLocation = latitudeKey == cachedLatitude && longitudeKey == cachedLongitude

        if (sameLocation && week == cachedWeek) {
            cachedPrior?.let { return it }
        }

        val annualMax = cachedAnnualMax.takeIf { sameLocation }
            ?: annualMaxOf(interpreter, latitudeKey, longitudeKey).also {
                Log.i(TAG, "Barrido anual recalculado para ($latitudeKey, $longitudeKey)")
            }

        runMeta(interpreter, latitudeKey, longitudeKey, week)
        val prior = MapSpeciesPrior(
            BirdNetPriorMath.buildMultipliers(speciesIds, metaOutput, annualMax),
        )

        cachedLatitude = latitudeKey
        cachedLongitude = longitudeKey
        cachedAnnualMax = annualMax
        cachedWeek = week
        cachedPrior = prior
        return prior
    }

    /**
     * Maximo de presencia de cada clase a lo largo del ano.
     *
     * No depende de la semana, solo de la ubicacion, asi que sobrevive al cambio
     * de dia: al pasar de semana basta una inferencia mas, no otras 48.
     */
    private fun annualMaxOf(
        interpreter: Interpreter,
        latitude: Double,
        longitude: Double,
    ): FloatArray {
        val maximum = FloatArray(BirdNetLabels.EXPECTED_COUNT)
        for (week in 1..BirdNetPriorMath.WEEKS_IN_YEAR) {
            runMeta(interpreter, latitude, longitude, week)
            for (index in maximum.indices) {
                if (metaOutput[index] > maximum[index]) maximum[index] = metaOutput[index]
            }
        }
        return maximum
    }

    /** Deja el resultado en [metaOutput]; el llamante debe consumirlo antes de repetir. */
    private fun runMeta(
        interpreter: Interpreter,
        latitude: Double,
        longitude: Double,
        week: Int,
    ) {
        inputBuffer.rewind()
        inputBuffer.asFloatBuffer().apply {
            put(latitude.toFloat())
            put(longitude.toFloat())
            put(BirdNetPriorMath.weekMeta(week))
        }
        inputBuffer.rewind()
        outputBuffer.rewind()

        // A diferencia del clasificador, aqui la salida ya es probabilidad: el
        // meta-modelo lleva la sigmoide dentro del grafo y no hay que aplicarla.
        interpreter.run(inputBuffer, outputBuffer)

        outputBuffer.rewind()
        outputBuffer.asFloatBuffer().get(metaOutput)
    }

    /**
     * Igual que en el clasificador: si el modelo no esta, el mensaje tiene que
     * decir exactamente que ejecutar. Es el fallo mas probable en un clon recien
     * hecho del repositorio, porque los .tflite no van en el control de versiones.
     */
    private fun resolveModelAsset(): String {
        val available = context.assets.list("").orEmpty().toSet()
        return META_MODEL_ASSET_CANDIDATES.firstOrNull { it in available }
            ?: throw FileNotFoundException(
                "No hay ningun meta-modelo de BirdNET en assets. Ejecuta " +
                    "scripts/fetch-models.ps1 (se buscaron: " +
                    "${META_MODEL_ASSET_CANDIDATES.joinToString()})",
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
     * Imprescindible llamarlo: el interprete retiene 14 MB mapeados que el
     * recolector de basura no libera por su cuenta.
     */
    override fun close() {
        interpreter?.close()
        interpreter = null
        speciesIds = emptyList()
        cachedLatitude = null
        cachedLongitude = null
        cachedAnnualMax = null
        cachedPrior = null
    }

    companion object {
        private const val TAG = "BirdNetPrior"

        /** `(latitud, longitud, weekMeta)`. */
        private const val META_INPUT_SIZE = 3

        /**
         * Un hilo basta: el modelo es minusculo y el barrido anual solo ocurre
         * al cambiar de sitio. Repartirlo en varios hilos competiria con el
         * clasificador de audio, que si va justo de tiempo.
         */
        private const val DEFAULT_THREAD_COUNT = 1

        /**
         * Solo hay una variante publicada del meta-modelo V2, pero se busca en
         * una lista por simetria con el clasificador: si algun dia aparece una
         * FP32 basta anadirla aqui.
         */
        val META_MODEL_ASSET_CANDIDATES = listOf(
            "BirdNET_GLOBAL_6K_V2.4_MData_Model_V2_FP16.tflite",
        )
    }
}

/**
 * Prior respaldado por una tabla de multiplicadores ya discretizados.
 *
 * Un speciesId desconocido devuelve el multiplicador neutro: el prior solo sabe
 * de las 6.522 clases del modelo, y no saber de una especie no es una razon para
 * castigarla.
 */
internal class MapSpeciesPrior(
    private val multipliers: Map<String, Float>,
    private val neutral: Float = BirdNetPriorMath.NEUTRAL_MULTIPLIER,
) : SpeciesPrior {
    override fun get(speciesId: String): Float = multipliers[speciesId] ?: neutral
}

/**
 * Toda la aritmetica del prior, separada del interprete para poder probarla en
 * la JVM. Nada de aqui necesita Android ni LiteRT.
 */
internal object BirdNetPriorMath {

    /** El "ano" del meta-modelo tiene 48 semanas, no 52. */
    const val WEEKS_IN_YEAR = 48

    /** Multiplicador de una especie sobre la que el prior no opina. */
    const val NEUTRAL_MULTIPLIER = 1f

    /**
     * Suelo del ultimo tramo. Nunca 0: ver la nota de
     * [BirdNetContextPriorProvider]. Con 0 la especie desaparece por
     * construccion; con 0.05 solo necesita una deteccion mucho mas clara.
     */
    const val FLOOR_MULTIPLIER = 0.05f

    private const val DEGREES_PER_WEEK = 7.5

    /** Divisor del cache de coordenadas: 2 decimales, ~1 km. */
    private const val COORDINATE_SCALE = 100.0

    /**
     * Semana 1..48 a partir del dia del ano.
     *
     * El divisor es 366 tambien en los anos comunes: es la convencion del propio
     * BirdNET y cambiarla a 365 desplazaria una semana el final del ano respecto
     * a los valores con los que se entreno el modelo.
     */
    fun weekOfYear(dayOfYear: Int): Int {
        val week = ceil(dayOfYear * WEEKS_IN_YEAR.toDouble() / 366.0).toInt()
        return week.coerceIn(1, WEEKS_IN_YEAR)
    }

    fun weekOfYear(epochMillis: Long, zoneId: ZoneId): Int =
        weekOfYear(Instant.ofEpochMilli(epochMillis).atZone(zoneId).dayOfYear)

    /**
     * Tercera componente de la entrada del meta-modelo.
     *
     * Es un coseno desplazado a [0, 2] en vez de la semana cruda porque asi el
     * 31 de diciembre y el 1 de enero quedan a distancia minima: para un ave el
     * ano es un ciclo, no una recta.
     */
    fun weekMeta(week: Int): Float =
        (cos(Math.toRadians(week * DEGREES_PER_WEEK)) + 1.0).toFloat()

    /**
     * Mezcla la presencia de la semana actual con el maximo anual, al 50%.
     *
     * Sin el maximo anual se perderian las migratorias adelantadas o rezagadas.
     * Sin la semana actual el prior seria puramente geografico y dejaria de
     * distinguir un invernante de un estival.
     */
    fun combineWithAnnualMax(weekly: Float, annualMax: Float): Float =
        0.5f * weekly + 0.5f * annualMax

    /**
     * Discretiza la probabilidad de presencia en un multiplicador.
     *
     * Multiplicar por la probabilidad cruda seria mucho mas fragil: los valores
     * del meta-modelo son diminutos y su escala no esta calibrada contra las
     * puntuaciones del clasificador, asi que un producto directo hundiria todo.
     * Cuatro tramos bastan para decir "normal aqui", "posible", "raro" y "no
     * deberia estar", que es toda la resolucion que el dato soporta.
     */
    fun multiplierFor(probability: Float): Float = when {
        probability >= 0.01f -> 1f
        probability >= 0.008f -> 0.8f
        probability >= 0.001f -> 0.5f
        else -> FLOOR_MULTIPLIER
    }

    /** Redondeo que sirve de clave de cache del barrido anual. */
    fun roundCoordinate(value: Double): Double =
        round(value * COORDINATE_SCALE) / COORDINATE_SCALE

    /**
     * Tabla speciesId -> multiplicador a partir de las dos salidas del
     * meta-modelo.
     *
     * Varias clases del tensor pueden colapsar en el mismo speciesId (BirdNET
     * separa subespecies y grupos que eBird agrupa). Se queda el multiplicador
     * mas alto: si alguna de esas formas es esperable aqui, la especie lo es.
     */
    fun buildMultipliers(
        speciesIds: List<String>,
        weekly: FloatArray,
        annualMax: FloatArray,
    ): Map<String, Float> {
        require(weekly.size >= speciesIds.size && annualMax.size >= speciesIds.size) {
            "Las salidas del meta-modelo (${weekly.size}, ${annualMax.size}) no cubren " +
                "las ${speciesIds.size} clases etiquetadas"
        }
        val multipliers = HashMap<String, Float>(speciesIds.size)
        for (index in speciesIds.indices) {
            val multiplier = multiplierFor(combineWithAnnualMax(weekly[index], annualMax[index]))
            val previous = multipliers[speciesIds[index]]
            if (previous == null || multiplier > previous) {
                multipliers[speciesIds[index]] = multiplier
            }
        }
        return multipliers
    }
}
