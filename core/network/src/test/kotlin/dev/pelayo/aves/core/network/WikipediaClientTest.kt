package dev.pelayo.aves.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WikipediaClientTest {

    /** Con la forma real de la respuesta de /page/summary. */
    private val robin = """
        {
          "type": "standard",
          "title": "Erithacus rubecula",
          "displaytitle": "<i>Erithacus rubecula</i>",
          "thumbnail": {"source": "https://upload.wikimedia.org/thumb/robin.jpg/320px-robin.jpg", "width": 320, "height": 240},
          "originalimage": {"source": "https://upload.wikimedia.org/robin.jpg", "width": 2000, "height": 1500},
          "lang": "es",
          "extract": "El petirrojo europeo (Erithacus rubecula) es una especie de ave paseriforme.",
          "content_urls": {
            "desktop": {"page": "https://es.wikipedia.org/wiki/Erithacus_rubecula"},
            "mobile": {"page": "https://es.m.wikipedia.org/wiki/Erithacus_rubecula"}
          }
        }
    """.trimIndent()

    private fun client(
        handler: (url: String) -> Pair<HttpStatusCode, String>,
        onRequest: (io.ktor.client.request.HttpRequestData) -> Unit = {},
    ): WikipediaClient {
        val engine = MockEngine { request ->
            onRequest(request)
            val (status, body) = handler(request.url.toString())
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return WikipediaClient(engine)
    }

    @Test
    fun `parsea el resumen con imagen y enlace`() = runTest {
        client({ HttpStatusCode.OK to robin }).use { wikipedia ->
            val summary = wikipedia.summary("Erithacus rubecula")

            assertNotNull(summary)
            assertEquals("Erithacus rubecula", summary!!.title)
            assertTrue(summary.extract.startsWith("El petirrojo europeo"))
            assertEquals("https://upload.wikimedia.org/thumb/robin.jpg/320px-robin.jpg", summary.thumbnailUrl)
            assertEquals("https://upload.wikimedia.org/robin.jpg", summary.originalImageUrl)
            // La movil, que es la que se abre desde un movil.
            assertEquals("https://es.m.wikipedia.org/wiki/Erithacus_rubecula", summary.pageUrl)
            assertEquals("es", summary.language)
        }
    }

    @Test
    fun `busca por nombre cientifico en la edicion pedida`() = runTest {
        var requested = ""
        client({ HttpStatusCode.OK to robin }, onRequest = { requested = it.url.toString() }).use { wikipedia ->
            wikipedia.summary("Erithacus rubecula", "es")
        }
        assertEquals("https://es.wikipedia.org/api/rest_v1/page/summary/Erithacus%20rubecula", requested)
    }

    @Test
    fun `manda un User-Agent que identifica a la app y no al usuario`() = runTest {
        var agent: String? = null
        client({ HttpStatusCode.OK to robin }, onRequest = { agent = it.headers[HttpHeaders.UserAgent] }).use {
            it.summary("Erithacus rubecula")
        }
        assertEquals(WikipediaClient.USER_AGENT, agent)
        assertTrue(agent!!.contains("Aves"))
    }

    @Test
    fun `sin articulo devuelve null y no revienta`() = runTest {
        client({ HttpStatusCode.NotFound to """{"type":"https://mediawiki.org/wiki/HyperSwitch/errors/not_found"}""" })
            .use { wikipedia ->
                assertNull(wikipedia.summary("Ave inventada"))
            }
    }

    @Test
    fun `una desambiguacion no vale como ficha`() = runTest {
        val disambiguation = robin.replace("\"type\": \"standard\"", "\"type\": \"disambiguation\"")
        client({ HttpStatusCode.OK to disambiguation }).use { wikipedia ->
            assertNull(wikipedia.summary("Mirlo"))
        }
    }

    @Test
    fun `cae a la edicion inglesa si la espanola no tiene articulo`() = runTest {
        val asked = ArrayList<String>()
        client({ url ->
            asked += url
            if (url.startsWith("https://es.")) HttpStatusCode.NotFound to "{}" else HttpStatusCode.OK to robin
        }).use { wikipedia ->
            val summary = wikipedia.summaryWithFallback("Erithacus rubecula")

            assertNotNull(summary)
            assertEquals("en", summary!!.language)
        }
        assertEquals(2, asked.size)
        assertTrue(asked[0].startsWith("https://es."))
        assertTrue(asked[1].startsWith("https://en."))
    }

    @Test
    fun `un fallo del servidor es un error, no un no hay ficha`() = runTest {
        // Cachear un 500 como "sin articulo" dejaria la especie sin foto para siempre.
        client({ HttpStatusCode.InternalServerError to "" }).use { wikipedia ->
            assertThrows(IOException::class.java) {
                kotlinx.coroutines.runBlocking { wikipedia.summary("Erithacus rubecula") }
            }
        }
    }
}
