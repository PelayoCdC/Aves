package dev.pelayo.aves.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.IOException

/** Lo que Wikipedia sabe de una especie, ya reducido a lo que ensena la ficha. */
data class WikipediaSummary(
    /** Titulo del articulo tal y como lo tiene Wikipedia. */
    val title: String,
    /** Primer parrafo, en texto plano. */
    val extract: String,
    /** Miniatura (~320 px de ancho) o null si el articulo no tiene imagen. */
    val thumbnailUrl: String?,
    /** La imagen original, para la cabecera de la ficha. */
    val originalImageUrl: String?,
    val pageUrl: String,
    /** "es" o "en": de que edicion salio, para decirlo en la atribucion. */
    val language: String,
)

/**
 * Cliente de la API REST de resumen de Wikipedia.
 *
 * Es la fuente de la foto y la descripcion de cada especie porque es la unica
 * gratuita, sin clave, con cobertura de las 11.000 especies y con una licencia
 * (CC BY-SA para el texto) que permite ensenarla citando la fuente. Se busca
 * por nombre cientifico: es el titulo mas estable entre ediciones, y la
 * espanola tiene redirecciones desde el binomio hacia el nombre comun.
 *
 * Se consulta UNA vez por especie y se guarda en el catalogo; la ficha no
 * vuelve a preguntar. Un 404 (no hay articulo) no es un error: es "no hay
 * ficha", y tambien se recuerda para no repetir la consulta.
 *
 * El User-Agent es obligatorio en la politica de Wikimedia y tiene que
 * identificar a la aplicacion, no a la persona: nada del usuario viaja aqui.
 */
class WikipediaClient(
    engine: HttpClientEngine? = null,
) : Closeable {

    private val client: HttpClient = createClient(engine)

    /**
     * Resumen del articulo de [title] en la edicion [language], o null si no
     * existe. Lanza [IOException] si la red fallo: eso NO debe cachearse.
     */
    suspend fun summary(title: String, language: String = DEFAULT_LANGUAGE): WikipediaSummary? {
        val url = "https://$language.wikipedia.org/api/rest_v1/page/summary/${title.trim().encodeURLPathPart()}"
        val response = try {
            client.get(url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "application/json")
            }
        } catch (e: IOException) {
            throw IOException("No se pudo contactar con Wikipedia", e)
        }

        return when (response.status.value) {
            in 200..299 -> WikipediaJson.decodeFromString(
                WikipediaSummaryResponse.serializer(),
                response.bodyAsText(),
            ).toSummary(language)

            404 -> null

            else -> throw IOException("Wikipedia respondio ${response.status}")
        }
    }

    /**
     * Prueba primero en la edicion pedida y, si no hay articulo, en la
     * inglesa: la cobertura de aves en ingles es practicamente total, y una
     * foto con texto en ingles es mejor que nada.
     */
    suspend fun summaryWithFallback(
        title: String,
        language: String = DEFAULT_LANGUAGE,
    ): WikipediaSummary? =
        summary(title, language) ?: if (language != FALLBACK_LANGUAGE) summary(title, FALLBACK_LANGUAGE) else null

    private fun createClient(engine: HttpClientEngine?): HttpClient {
        val configure: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            }
            expectSuccess = false
        }
        return if (engine != null) HttpClient(engine, configure) else HttpClient(OkHttp, configure)
    }

    override fun close() = client.close()

    companion object {
        const val DEFAULT_LANGUAGE = "es"
        const val FALLBACK_LANGUAGE = "en"

        /** Identifica a la app, como pide Wikimedia. Ni rastro del usuario. */
        const val USER_AGENT = "Aves/0.1 (Android; identificacion de aves; github.com/PelayoCdC/Aves)"

        private const val REQUEST_TIMEOUT_MILLIS = 15_000L
        private const val CONNECT_TIMEOUT_MILLIS = 10_000L
    }
}

internal val WikipediaJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
internal data class WikipediaSummaryResponse(
    val title: String = "",
    val extract: String = "",
    val thumbnail: WikipediaImage? = null,
    @SerialName("originalimage") val originalImage: WikipediaImage? = null,
    @SerialName("content_urls") val contentUrls: WikipediaContentUrls? = null,
    /** "standard", "disambiguation", "no-extract"... Solo interesa el primero. */
    val type: String = "standard",
)

@Serializable
internal data class WikipediaImage(val source: String = "")

@Serializable
internal data class WikipediaContentUrls(val desktop: WikipediaPageUrls? = null, val mobile: WikipediaPageUrls? = null)

@Serializable
internal data class WikipediaPageUrls(val page: String = "")

internal fun WikipediaSummaryResponse.toSummary(language: String): WikipediaSummary? {
    // Una desambiguacion no es una ficha de especie; devolverla confundiria.
    if (type != "standard" || extract.isBlank()) return null
    val page = contentUrls?.mobile?.page?.takeIf { it.isNotBlank() }
        ?: contentUrls?.desktop?.page?.takeIf { it.isNotBlank() }
        ?: "https://$language.wikipedia.org/wiki/${title.encodeURLPathPart()}"
    return WikipediaSummary(
        title = title,
        extract = extract.trim(),
        thumbnailUrl = thumbnail?.source?.takeIf { it.isNotBlank() },
        originalImageUrl = originalImage?.source?.takeIf { it.isNotBlank() },
        pageUrl = page,
        language = language,
    )
}
