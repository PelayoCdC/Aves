package dev.pelayo.aves.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pelayo.aves.core.database.dao.LookalikeCount
import dev.pelayo.aves.core.database.entity.ObservationCandidateEntity
import dev.pelayo.aves.core.database.entity.ObservationEntity
import dev.pelayo.aves.core.model.ObservationSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Se confunde con...": sale de los candidatos de las detecciones propias, en
 * los dos sentidos, y solo de lo confirmado. No necesita el catalogo ni los
 * modelos, asi que corre en la CI.
 */
@RunWith(AndroidJUnit4::class)
class LookalikesDaoTest {

    private lateinit var database: AvesDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AvesDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `cuenta las alternativas en los dos sentidos y solo lo confirmado`() = runBlocking {
        val dao = database.observationDao()
        // Mosquitero comun confirmado dos veces, con el iberico como alternativa.
        val a = dao.insert(observation("phycol", confirmed = true))
        val b = dao.insert(observation("phycol", confirmed = true))
        dao.insertCandidates(listOf(candidate(a, "phyibe", 0), candidate(b, "phyibe", 0), candidate(b, "sylatr", 1)))
        // Iberico confirmado una vez, con el comun como alternativa: cuenta tambien.
        val c = dao.insert(observation("phyibe", confirmed = true))
        dao.insertCandidates(listOf(candidate(c, "phycol", 0)))
        // Lo pendiente no cuenta: aun no se sabe que era.
        val d = dao.insert(observation("phycol", confirmed = false))
        dao.insertCandidates(listOf(candidate(d, "phyibe", 0)))

        assertEquals(
            listOf(LookalikeCount("phyibe", 3), LookalikeCount("sylatr", 1)),
            dao.lookalikesOf("phycol", limit = 4),
        )
    }

    @Test
    fun `el mejor recorte es el de mas confianza entre lo confirmado con audio`() = runBlocking {
        val dao = database.observationDao()
        dao.insert(observation("turmer", confirmed = true, confidence = 0.5f, media = "/c/flojo.m4a"))
        dao.insert(observation("turmer", confirmed = true, confidence = 0.9f, media = "/c/bueno.m4a"))
        dao.insert(observation("turmer", confirmed = false, confidence = 0.99f, media = "/c/pendiente.m4a"))

        assertEquals("/c/bueno.m4a", dao.bestClipOf("turmer"))
        assertNull(dao.bestClipOf("erirub"))
    }

    private fun observation(
        speciesId: String,
        confirmed: Boolean,
        confidence: Float = 0.8f,
        media: String? = null,
    ) = ObservationEntity(
        speciesId = speciesId,
        sessionId = null,
        timestamp = 1_000L,
        latitude = null,
        longitude = null,
        source = ObservationSource.AUDIO,
        rawConfidence = confidence,
        adjustedConfidence = confidence,
        modelId = "prueba",
        mediaPath = media,
        userConfirmed = confirmed,
        rarityTierAtCapture = -1,
    )

    private fun candidate(observationId: Long, speciesId: String, rank: Int) =
        ObservationCandidateEntity(observationId = observationId, speciesId = speciesId, rank = rank, score = 0.3f)
}
