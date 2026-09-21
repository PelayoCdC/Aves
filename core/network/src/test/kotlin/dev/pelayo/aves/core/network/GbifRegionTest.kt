package dev.pelayo.aves.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Las dos llamadas que sostienen el filtro por zona de la Pokedex.
 */
class GbifRegionTest {

    private fun page(names: List<Pair<String, Long>>, total: Long = 52_058_506): String {
        val counts = names.joinToString(",") { (name, count) -> """{"name":"$name","count":$count}""" }
        return """{"offset":0,"limit":0,"count":$total,"results":[],""" +
            """"facets":[{"field":"SCIENTIFIC_NAME","counts":[$counts]}]}"""
    }

    private fun client(onRequest: (Url) -> String): GbifClient {
        val engine = MockEngine { request ->
            respond(
                content = onRequest(request.url),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return GbifClient(engine, initialBackoffMillis = 1)
    }

    @Test
    fun `lista un pais por su codigo y con minimo de registros`() = runTest {
        var asked: Url? = null
        client { url ->
            asked = url
            page(listOf("Hirundo rustica Linnaeus, 1758" to 1_364_333L))
        }.use { gbif ->
            val summary = gbif.speciesInRegion(countryCode = "ES")

            assertEquals(1_364_333L, summary.speciesCounts["Hirundo rustica Linnaeus, 1758"])
            assertEquals(52_058_506L, summary.total)
        }
        assertEquals("ES", asked!!.parameters["country"])
        assertEquals("212", asked!!.parameters["taxonKey"])
        assertEquals("5", asked!!.parameters["facetMincount"])
        assertNull(asked!!.parameters["gadmGid"])
    }

    @Test
    fun `una provincia se pide por su identificador GADM y no por el pais`() = runTest {
        var asked: Url? = null
        client { url ->
            asked = url
            page(emptyList())
        }.use { gbif -> gbif.speciesInRegion(countryCode = "ES", gadmGid = "ESP.3.1_1") }

        assertEquals("ESP.3.1_1", asked!!.parameters["gadmGid"])
        assertNull(asked!!.parameters["country"])
    }

    @Test
    fun `pagina la faceta hasta agotarla`() = runTest {
        // Un pais tiene mas de mil nombres: quedarse con la primera pagina
        // dejaria fuera justo a las especies escasas, que son las que dan juego.
        val offsets = ArrayList<String?>()
        client { url ->
            offsets += url.parameters["facetOffset"]
            when (url.parameters["facetOffset"]) {
                "0" -> page(List(1000) { "Genus especie$it Autor, 1900" to 10L })
                else -> page(listOf("Ultima especie Autor, 1900" to 6L))
            }
        }.use { gbif ->
            val summary = gbif.speciesInRegion(countryCode = "ES")
            assertEquals(1001, summary.speciesCounts.size)
        }
        assertEquals(listOf("0", "1000"), offsets)
    }

    @Test
    fun `sin pais ni region no hay nada que pedir`() {
        client { page(emptyList()) }.use { gbif ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { gbif.speciesInRegion() }
            }
        }
    }

    @Test
    fun `la geocodificacion inversa devuelve pais, region y provincia`() = runTest {
        val body = """
            [{"id":"ESP","type":"GADM0","source":"http://gadm.org/","title":"Spain","isoCountryCode2Digit":"ES","distance":0.0},
             {"id":"ESP.8_1","type":"GADM1","title":"Comunidad de Madrid","isoCountryCode2Digit":"ES","distance":0.0},
             {"id":"ESP.8.1_1","type":"GADM2","title":"Madrid","isoCountryCode2Digit":"ES","distance":0.0},
             {"id":"EUROPE","type":"Continent","title":"EUROPE","isoCountryCode2Digit":"","distance":0.0}]
        """.trimIndent()
        var asked: Url? = null
        client { url ->
            asked = url
            body
        }.use { gbif ->
            val layers = gbif.reverseGeocode(40.416775, -3.703790)

            assertEquals("ESP.8.1_1", layers.first { it.type == GbifLocation.TYPE_PROVINCE }.id)
            assertEquals("Comunidad de Madrid", layers.first { it.type == GbifLocation.TYPE_REGION }.name)
            assertEquals("ES", layers.first { it.type == GbifLocation.TYPE_COUNTRY }.isoCountryCode2Digit)
        }
        // Un decimal (~10 km): para saber la provincia sobra, y no sale mas del movil.
        assertEquals("40.4", asked!!.parameters["lat"])
        assertEquals("-3.7", asked!!.parameters["lng"])
    }
}
