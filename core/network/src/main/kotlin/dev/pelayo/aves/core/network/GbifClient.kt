package dev.pelayo.aves.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import java.io.Closeable
import java.io.IOException
import kotlin.math.round

/**
 * GBIF no ha podido responder AHORA.
 *
 * Es deliberadamente distinto de un resumen vacio. Un resumen vacio significa
 * "GBIF no tiene registros de aves en esta celda", que es informacion real y se
 * puede cachear. Esta excepcion significa "no se sabe", y cachear eso dejaria la
 * zona marcada como sin aves hasta la siguiente caducidad.
 *
 * Confundir las dos cosas es el error clasico al cachear datos de red, y aqui
 * tendria una consecuencia concreta y absurda: todas las especies pasarian a ser
 * "excepcionales" por haber tenido mala cobertura un momento.
 */
class GbifUnavailableException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * Cliente de la API de ocurrencias de GBIF.
 *
 * GBIF es la fuente elegida para la rareza porque **no pide API key y no tiene
 * clausula no comercial**, a diferencia de eBird y xeno-canto. Eso permite que
 * el flavor publicable la use sin problemas de licencia.
 *
 * Todas las consultas van con `limit=0`: no interesan los registros
 * individuales, solo el recuento y las facetas. Eso convierte una consulta que
 * podria devolver megabytes en una de unos pocos kilobytes.
 */
class GbifClient(
    engine: HttpClientEngine? = null,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val initialBackoffMillis: Long = DEFAULT_INITIAL_BACKOFF_MILLIS,
) : Closeable {

    private val client: HttpClient = createClient(engine)

    /**
     * Ocurrencias de aves en una celda y mes, agrupadas por nombre cientifico.
     *
     * Es la via principal: nuestro `speciesId` deriva del nombre cientifico, asi
     * que el cruce es directo. La alternativa por speciesKey exigiria emparejar
     * las 6.419 especies del catalogo contra el backbone de GBIF, miles de
     * llamadas para obtener lo mismo.
     *
     * @param month 1..12, o null para el año entero.
     * @throws GbifUnavailableException si GBIF no responde. NO devuelve un
     *   resumen vacio: eso significaria "aqui no hay aves".
     */
    suspend fun occurrencesInCell(
        latitude: Double,
        longitude: Double,
        radiusKm: Int = DEFAULT_RADIUS_KM,
        month: Int? = null,
        facetLimit: Int = DEFAULT_FACET_LIMIT,
    ): GbifOccurrenceSummary = search(
        latitude = latitude,
        longitude = longitude,
        radiusKm = radiusKm,
        month = month,
        facet = FACET_PARAM_SCIENTIFIC_NAME,
        facetLimit = facetLimit,
    ).toNameSummary()

    /**
     * Igual que [occurrencesInCell] pero agrupando por speciesKey.
     *
     * La clave numerica es inequivoca y no depende de la autoria ni de
     * sinonimos, asi que es preferible cuando se trabaja con UNA especie
     * concreta y se conoce su clave.
     */
    suspend fun occurrencesInCellBySpeciesKey(
        latitude: Double,
        longitude: Double,
        radiusKm: Int = DEFAULT_RADIUS_KM,
        month: Int? = null,
        facetLimit: Int = DEFAULT_FACET_LIMIT,
    ): GbifSpeciesKeySummary = search(
        latitude = latitude,
        longitude = longitude,
        radiusKm = radiusKm,
        month = month,
        facet = FACET_PARAM_SPECIES_KEY,
        facetLimit = facetLimit,
    ).toSpeciesKeySummary()

    /**
     * Perfil fenologico de una especie: cuantas ocurrencias tiene cada mes.
     *
     * Es lo que permite distinguir a una residente de una migratoria, y por
     * tanto saber si una deteccion en marzo es normal o notable.
     *
     * @return mes (1..12) -> ocurrencias. Los meses sin registros no aparecen.
     */
    suspend fun monthlyProfile(
        scientificName: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Int = DEFAULT_RADIUS_KM,
    ): Map<Int, Long> = request { url ->
        url.parameter("scientificName", scientificName)
        url.parameter("geoDistance", geoDistance(latitude, longitude, radiusKm))
        url.parameter("limit", 0)
        url.parameter("facet", FACET_PARAM_MONTH)
        url.parameter("facetLimit", 12)
    }.countsByIntKey(FACET_FIELD_MONTH)

    /** Variante por clave, mas fiable cuando se conoce el speciesKey. */
    suspend fun monthlyProfile(
        speciesKey: Int,
        latitude: Double,
        longitude: Double,
        radiusKm: Int = DEFAULT_RADIUS_KM,
    ): Map<Int, Long> = request { url ->
        url.parameter("speciesKey", speciesKey)
        url.parameter("geoDistance", geoDistance(latitude, longitude, radiusKm))
        url.parameter("limit", 0)
        url.parameter("facet", FACET_PARAM_MONTH)
        url.parameter("facetLimit", 12)
    }.countsByIntKey(FACET_FIELD_MONTH)

    /**
     * Todas las especies de ave con registros en una zona administrativa: la
     * "lista de la provincia" o "del pais".
     *
     * Es lo que alimenta el filtro por zona de la Pokedex. A diferencia de
     * [occurrencesInCell] aqui NO hay tope de 500: un pais tiene mas especies
     * que eso, asi que se pagina la faceta hasta agotarla.
     *
     * @param countryCode ISO de dos letras ("ES"), o null.
     * @param gadmGid identificador GADM de una region o provincia
     *   ("ESP.3_1", "ESP.3.1_1"), o null. Tiene prioridad sobre el pais.
     * @param minCount registros minimos para que una especie cuente. Con 1, la
     *   lista de un pais se llena de escapes de jaula y errores de
     *   identificacion de un solo registro.
     */
    suspend fun speciesInRegion(
        countryCode: String? = null,
        gadmGid: String? = null,
        minCount: Int = DEFAULT_REGION_MIN_COUNT,
    ): GbifOccurrenceSummary {
        require(countryCode != null || gadmGid != null) { "Hace falta un pais o una region" }

        val counts = LinkedHashMap<String, Long>()
        var total = 0L
        var offset = 0
        while (offset < MAX_REGION_FACETS) {
            val page = request { url ->
                url.parameter("taxonKey", GBIF_TAXON_KEY_AVES)
                if (gadmGid != null) url.parameter("gadmGid", gadmGid) else url.parameter("country", countryCode)
                url.parameter("limit", 0)
                url.parameter("facet", FACET_PARAM_SCIENTIFIC_NAME)
                url.parameter("facetLimit", REGION_FACET_PAGE)
                url.parameter("facetOffset", offset)
                url.parameter("facetMincount", minCount)
            }
            total = page.count
            val names = page.countsByName(FACET_FIELD_SCIENTIFIC_NAME)
            counts.putAll(names)
            if (names.size < REGION_FACET_PAGE) break
            offset += REGION_FACET_PAGE
        }
        return GbifOccurrenceSummary(total = total, speciesCounts = counts)
    }

    /**
     * A que pais, region y provincia pertenece un punto, segun GADM.
     *
     * Devuelve los identificadores que despues entiende [speciesInRegion], que
     * es lo que hace util esta llamada frente al geocodificador del sistema: no
     * hay que traducir "Asturias" a nada.
     *
     * Las coordenadas se redondean a un decimal (~10 km) antes de salir del
     * movil: la app promete que la ubicacion es aproximada por diseno, y para
     * saber la provincia sobra. A cambio, a menos de unos kilometros de una
     * linde puede salir la provincia vecina; el menu de zonas deja corregirlo
     * subiendo a la region o al pais.
     */
    suspend fun reverseGeocode(latitude: Double, longitude: Double): List<GbifLocation> {
        val response = try {
            client.get(REVERSE_GEOCODE_URL) {
                parameter("lat", roundCoordinate(latitude))
                parameter("lng", roundCoordinate(longitude))
            }
        } catch (e: IOException) {
            throw GbifUnavailableException("No se pudo contactar con GBIF", e)
        }
        if (!response.status.isSuccess()) {
            throw GbifUnavailableException("GBIF respondio ${response.status} al geocodificar")
        }
        return GbifJson.decodeFromString(
            ListSerializer(GbifLocation.serializer()),
            response.bodyAsText(),
        )
    }

    private fun roundCoordinate(value: Double): Double = round(value * 10.0) / 10.0

    private suspend fun search(
        latitude: Double,
        longitude: Double,
        radiusKm: Int,
        month: Int?,
        facet: String,
        facetLimit: Int,
    ): GbifOccurrenceResponse = request { url ->
        url.parameter("taxonKey", GBIF_TAXON_KEY_AVES)
        url.parameter("geoDistance", geoDistance(latitude, longitude, radiusKm))
        month?.let { url.parameter("month", it) }
        url.parameter("limit", 0)
        url.parameter("facet", facet)
        url.parameter("facetLimit", facetLimit)
    }

    /**
     * Ejecuta la consulta con reintentos y backoff exponencial.
     *
     * GBIF no documenta un limite de peticiones duro, pero devuelve 429 segun la
     * carga del servidor. Como la app pide doce meses seguidos al entrar en una
     * celda nueva, tropezar con uno es perfectamente normal y no deberia costar
     * la rareza de toda la zona.
     *
     * Solo se reintentan los fallos que pueden pasar solos: 429 y errores 5xx.
     * Un 400 significa que la consulta esta mal construida y reintentarla mil
     * veces no la va a arreglar.
     */
    private suspend fun request(
        configure: (io.ktor.client.request.HttpRequestBuilder) -> Unit,
    ): GbifOccurrenceResponse {
        var backoff = initialBackoffMillis
        var lastError: Throwable? = null

        repeat(maxAttempts) { attempt ->
            val response: HttpResponse = try {
                client.get(OCCURRENCE_SEARCH_URL) { configure(this) }
            } catch (e: IOException) {
                lastError = e
                if (attempt < maxAttempts - 1) {
                    delay(backoff)
                    backoff *= 2
                    return@repeat
                }
                throw GbifUnavailableException("No se pudo contactar con GBIF", e)
            }

            when {
                response.status.isSuccess() ->
                    return GbifJson.decodeFromString(
                        GbifOccurrenceResponse.serializer(),
                        response.bodyAsText(),
                    )

                response.status.value == TOO_MANY_REQUESTS || response.status.value >= 500 -> {
                    lastError = GbifUnavailableException("GBIF respondio ${response.status}")
                    if (attempt < maxAttempts - 1) {
                        delay(backoff)
                        backoff *= 2
                    }
                }

                else -> throw GbifUnavailableException(
                    "GBIF rechazo la consulta con ${response.status}. " +
                        "Esto no se reintenta: indica una consulta mal construida.",
                )
            }
        }

        throw GbifUnavailableException(
            "GBIF no respondio tras $maxAttempts intentos",
            lastError,
        )
    }

    private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

    /** Formato exacto que espera GBIF: `lat,lon,Nkm`. */
    private fun geoDistance(latitude: Double, longitude: Double, radiusKm: Int): String =
        "$latitude,$longitude,${radiusKm}km"

    private fun createClient(engine: HttpClientEngine?): HttpClient {
        val configure: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
            install(ContentNegotiation) { json(GbifJson) }
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            }
            // Los estados de error se gestionan a mano en `request`, que es quien
            // sabe cuales merece la pena reintentar.
            expectSuccess = false
        }
        return if (engine != null) HttpClient(engine, configure) else HttpClient(OkHttp, configure)
    }

    override fun close() = client.close()

    companion object {
        const val OCCURRENCE_SEARCH_URL = "https://api.gbif.org/v1/occurrence/search"
        const val REVERSE_GEOCODE_URL = "https://api.gbif.org/v1/geocode/reverse"

        /** Registros minimos para que una especie entre en la lista de una zona. */
        const val DEFAULT_REGION_MIN_COUNT = 5

        /** Tamano de pagina de la faceta al listar una zona. */
        private const val REGION_FACET_PAGE = 1000

        /** Tope de seguridad: ningun pais tiene 5.000 nombres de ave distintos con 5 registros. */
        private const val MAX_REGION_FACETS = 5000

        /** Ojo: el parametro va en camelCase, la respuesta lo rotula en mayusculas. */
        const val FACET_PARAM_SCIENTIFIC_NAME = "scientificName"
        const val FACET_PARAM_SPECIES_KEY = "speciesKey"
        const val FACET_PARAM_MONTH = "month"

        const val DEFAULT_RADIUS_KM = 50

        /**
         * Suficiente para cubrir la avifauna de cualquier celda: las zonas mas
         * ricas del mundo rondan las 700 especies, y una celda de 50 km no llega.
         */
        const val DEFAULT_FACET_LIMIT = 500

        private const val DEFAULT_MAX_ATTEMPTS = 3
        private const val DEFAULT_INITIAL_BACKOFF_MILLIS = 500L
        private const val TOO_MANY_REQUESTS = 429
        private const val REQUEST_TIMEOUT_MILLIS = 30_000L
        private const val CONNECT_TIMEOUT_MILLIS = 15_000L
    }
}
