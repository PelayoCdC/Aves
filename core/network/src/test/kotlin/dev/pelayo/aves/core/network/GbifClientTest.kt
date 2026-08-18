package dev.pelayo.aves.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GbifClientTest {

    /**
     * Respuesta con la forma REAL de GBIF, capturada de una consulta cerca de
     * Oviedo en junio. Inventarse el formato seria el error clasico: la autoria
     * aparece a veces entre parentesis, y el campo de la faceta se rotula en
     * mayusculas aunque el parametro vaya en camelCase.
     */
    private val realisticResponse = """
        {
          "offset": 0, "limit": 0, "endOfRecords": false, "count": 40839,
          "results": [],
          "facets": [{
            "field": "SCIENTIFIC_NAME",
            "counts": [
              {"name": "Hirundo rustica Linnaeus, 1758", "count": 1825},
              {"name": "Turdus merula Linnaeus, 1758", "count": 1802},
              {"name": "Erithacus rubecula (Linnaeus, 1758)", "count": 1155}
            ]
          }]
        }
    """.trimIndent()

    private fun clientRespondingWith(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        onRequest: (String) -> Unit = {},
    ): GbifClient {
        val engine = MockEngine { request ->
            onRequest(request.url.toString())
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return GbifClient(engine, initialBackoffMillis = 1)
    }

    @Test
    fun `parsea las facetas por nombre cientifico`() = runTest {
        clientRespondingWith(realisticResponse).use { client ->
            val summary = client.occurrencesInCell(43.36, -5.85, month = 6)

            assertEquals(40_839L, summary.total)
            assertEquals(3, summary.speciesCounts.size)
            assertEquals(1_802L, summary.speciesCounts["Turdus merula Linnaeus, 1758"])
            assertEquals(1_155L, summary.speciesCounts["Erithacus rubecula (Linnaeus, 1758)"])
        }
    }

    @Test
    fun `devuelve el nombre crudo con la autoria`() = runTest {
        // Recortar la autoria aqui perderia informacion de forma irreversible.
        // Normalizar es trabajo de :core:data, que conoce el formato de nuestros
        // speciesId.
        clientRespondingWith(realisticResponse).use { client ->
            val names = client.occurrencesInCell(43.36, -5.85).speciesCounts.keys
            assertTrue(names.all { it.contains("Linnaeus") })
        }
    }

    @Test
    fun `construye la consulta con el formato que espera GBIF`() = runTest {
        var requestedUrl = ""
        clientRespondingWith(realisticResponse, onRequest = { requestedUrl = it }).use { client ->
            client.occurrencesInCell(43.3619, -5.8494, radiusKm = 50, month = 6)
        }

        assertTrue("clase Aves", requestedUrl.contains("taxonKey=212"))
        // El formato lat,lon,Nkm es literal: GBIF rechaza cualquier variante.
        assertTrue("geoDistance: $requestedUrl", requestedUrl.contains("43.3619%2C-5.8494%2C50km"))
        assertTrue("mes", requestedUrl.contains("month=6"))
        // limit=0 convierte una respuesta de megabytes en una de kilobytes.
        assertTrue("limit=0", requestedUrl.contains("limit=0"))
        assertTrue("faceta en camelCase", requestedUrl.contains("facet=scientificName"))
    }

    @Test
    fun `omite el mes cuando se pide el año entero`() = runTest {
        var requestedUrl = ""
        clientRespondingWith(realisticResponse, onRequest = { requestedUrl = it }).use { client ->
            client.occurrencesInCell(43.36, -5.85, month = null)
        }
        assertTrue(!requestedUrl.contains("month="))
    }

    @Test
    fun `ignora campos que no conoce`() = runTest {
        // GBIF anade campos sin previo aviso; reventar por uno nuevo cuando solo
        // se leen count y facets seria absurdo.
        val withExtras = """
            {"count": 10, "campoNuevoDeGbif": {"a": 1}, "diagnostics": null,
             "facets": [{"field": "SCIENTIFIC_NAME", "otraCosa": 5,
                         "counts": [{"name": "Parus major", "count": 10, "extra": true}]}]}
        """.trimIndent()

        clientRespondingWith(withExtras).use { client ->
            val summary = client.occurrencesInCell(43.36, -5.85)
            assertEquals(10L, summary.total)
            assertEquals(10L, summary.speciesCounts["Parus major"])
        }
    }

    @Test
    fun `una respuesta sin facetas no revienta`() = runTest {
        clientRespondingWith("""{"count": 0, "facets": []}""").use { client ->
            val summary = client.occurrencesInCell(43.36, -5.85)
            assertTrue(summary.isEmpty)
        }
    }

    @Test
    fun `busca la faceta por nombre y no por posicion`() = runTest {
        // Si algun dia la consulta pide dos facetas, quedarse con la de la
        // posicion 0 seria una bomba de relojeria.
        val twoFacets = """
            {"count": 5, "facets": [
              {"field": "MONTH", "counts": [{"name": "6", "count": 5}]},
              {"field": "SCIENTIFIC_NAME", "counts": [{"name": "Parus major", "count": 5}]}]}
        """.trimIndent()

        clientRespondingWith(twoFacets).use { client ->
            assertEquals(5L, client.occurrencesInCell(43.36, -5.85).speciesCounts["Parus major"])
        }
    }

    @Test
    fun `parsea el perfil fenologico por meses`() = runTest {
        val monthly = """
            {"count": 100, "facets": [{"field": "MONTH", "counts": [
              {"name": "6", "count": 60}, {"name": "7", "count": 30}, {"name": "1", "count": 10}]}]}
        """.trimIndent()

        clientRespondingWith(monthly).use { client ->
            val profile = client.monthlyProfile("Hirundo rustica", 43.36, -5.85)
            assertEquals(60L, profile[6])
            assertEquals(10L, profile[1])
            assertEquals("los meses sin registros no aparecen", 3, profile.size)
        }
    }

    @Test
    fun `reintenta ante un 429 y acaba fallando de forma distinguible`() = runTest {
        var attempts = 0
        val engine = MockEngine {
            attempts++
            respondError(HttpStatusCode.TooManyRequests)
        }

        GbifClient(engine, maxAttempts = 3, initialBackoffMillis = 1).use { client ->
            // Un fallo de red NO puede parecer "aqui no hay aves": cachear eso
            // marcaria toda la zona como sin especies hasta la caducidad.
            assertThrows(GbifUnavailableException::class.java) {
                kotlinx.coroutines.runBlocking { client.occurrencesInCell(43.36, -5.85) }
            }
        }
        assertEquals("debe reintentar tres veces", 3, attempts)
    }

    @Test
    fun `reintenta ante un error de servidor`() = runTest {
        var attempts = 0
        val engine = MockEngine { request ->
            attempts++
            if (attempts < 3) {
                respondError(HttpStatusCode.InternalServerError)
            } else {
                respond(
                    realisticResponse,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }

        GbifClient(engine, maxAttempts = 4, initialBackoffMillis = 1).use { client ->
            val summary = client.occurrencesInCell(43.36, -5.85)
            assertEquals("debe recuperarse al tercer intento", 40_839L, summary.total)
        }
        assertEquals(3, attempts)
    }

    @Test
    fun `no reintenta una consulta mal construida`() = runTest {
        var attempts = 0
        val engine = MockEngine {
            attempts++
            respondError(HttpStatusCode.BadRequest)
        }

        GbifClient(engine, maxAttempts = 3, initialBackoffMillis = 1).use { client ->
            assertThrows(GbifUnavailableException::class.java) {
                kotlinx.coroutines.runBlocking { client.occurrencesInCell(43.36, -5.85) }
            }
        }
        // Un 400 no se arregla insistiendo: la consulta esta mal.
        assertEquals("no debe reintentarse", 1, attempts)
    }
}
