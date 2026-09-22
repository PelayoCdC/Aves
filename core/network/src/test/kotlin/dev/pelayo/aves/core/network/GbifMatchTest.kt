package dev.pelayo.aves.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Del nombre cientifico a la clave del taxon en GBIF, que es lo que pide el mapa. */
class GbifMatchTest {

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, onRequest: (Url) -> String): GbifClient {
        val engine = MockEngine { request ->
            respond(
                content = onRequest(request.url),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return GbifClient(engine, initialBackoffMillis = 1)
    }

    @Test
    fun `una especie exacta da su usageKey`() = runTest {
        var asked: Url? = null
        client { url ->
            asked = url
            """{"usageKey":2490719,"scientificName":"Turdus merula Linnaeus, 1758","rank":"SPECIES","status":"ACCEPTED","confidence":100,"matchType":"EXACT","classKey":212}"""
        }.use { gbif ->
            assertEquals(2490719, gbif.matchSpeciesKey("Turdus merula"))
        }
        assertEquals("Turdus merula", asked!!.parameters["name"])
        // Acotado a las aves: "Corvus" es tambien un genero de escarabajos.
        assertEquals("Aves", asked!!.parameters["class"])
    }

    @Test
    fun `una subespecie devuelve la clave de su especie`() = runTest {
        client {
            """{"usageKey":6171819,"speciesKey":2490719,"rank":"SUBSPECIES","matchType":"EXACT","confidence":98}"""
        }.use { gbif ->
            assertEquals(2490719, gbif.matchSpeciesKey("Turdus merula cabrerae"))
        }
    }

    @Test
    fun `sin coincidencia no hay clave`() = runTest {
        client { """{"confidence":100,"matchType":"NONE","synonym":false}""" }.use { gbif ->
            assertNull(gbif.matchSpeciesKey("Avis inventada"))
        }
    }

    @Test
    fun `casar solo con el genero tampoco vale`() = runTest {
        // GBIF devuelve el genero cuando no encuentra la especie: pintar el mapa
        // de todo el genero seria mentir.
        client {
            """{"usageKey":2490714,"rank":"GENUS","matchType":"HIGHERRANK","confidence":94}"""
        }.use { gbif ->
            assertNull(gbif.matchSpeciesKey("Turdus nadaensis"))
        }
    }

    @Test
    fun `sin red se lanza, no se devuelve null`() = runTest {
        client(status = HttpStatusCode.ServiceUnavailable) { "" }.use { gbif ->
            val result = runCatching { gbif.matchSpeciesKey("Turdus merula") }
            assertTrue(result.exceptionOrNull() is GbifUnavailableException)
        }
    }
}
