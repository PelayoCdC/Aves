package dev.pelayo.aves.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pelayo.aves.core.database.entity.ListeningSessionEntity
import dev.pelayo.aves.core.database.entity.ObservationEntity
import dev.pelayo.aves.core.model.ObservationSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogAndSessionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AvesDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AvesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    // --- Semilla del catalogo -----------------------------------------------

    @Test
    fun siembraElCatalogoCompletoDesdeLosAssets() = runBlocking {
        val seeded = CatalogSeeder(context, database).seedIfNeeded()

        assertEquals("especies del catalogo", 6419, seeded)
        assertEquals(6419, database.speciesDao().count())
        assertEquals(6522, database.modelLabelDao().countFor(CatalogSeeder.BIRDNET_MODEL_ID))
    }

    @Test
    fun elCrossWalkDejaSinMapearSoloLoQueNoEsUnAve() = runBlocking {
        CatalogSeeder(context, database).seedIfNeeded()

        // 11 clases de no-evento (perro, motor, sirena, disparo, ruido, ambiente,
        // fuegos artificiales, herramientas y las tres de humano) mas 90 animales
        // que BirdNET incluye pero no son aves: ranas, sapos, grillos, katidos,
        // un ciervo y algun mono.
        assertEquals(101, database.modelLabelDao().unmappedCountFor(CatalogSeeder.BIRDNET_MODEL_ID))
    }

    @Test
    fun resuelveEspeciesConocidasConSuNombreEnEspanol() = runBlocking {
        CatalogSeeder(context, database).seedIfNeeded()

        val petirrojo = database.speciesDao().findById("erithacus_rubecula")
        assertNotNull("el petirrojo europeo debe estar en el catalogo", petirrojo)
        assertEquals("Erithacus rubecula", petirrojo!!.scientificName)
        assertEquals("Muscicapidae", petirrojo.family)
        assertEquals("Passeriformes", petirrojo.order)
        assertTrue(petirrojo.defaultCommonName.contains("Petirrojo"))
        assertNotNull("debe conservar el codigo eBird para el cruce con GBIF", petirrojo.ebirdCode)
    }

    @Test
    fun recuperaLasAvesCuyoCodigoEbirdCaduco() = runBlocking {
        CatalogSeeder(context, database).seedIfNeeded()

        // La gaviota argentea es una de las 38 clases cuyo codigo eBird de 2023
        // ya no existe: solo se mapea gracias al respaldo por nombre cientifico.
        assertNotNull(database.speciesDao().findById("larus_argentatus"))

        // Y el carbonero japones es el unico caso que hubo que auditar a mano:
        // eBird lo agrupo dentro de Parus major.
        val labels = database.modelLabelDao().labelsFor(CatalogSeeder.BIRDNET_MODEL_ID)
        val parusMinor = labels.first { it.rawLabel.startsWith("Parus minor_") }
        assertEquals("parus_major", parusMinor.speciesId)
    }

    @Test
    fun sembrarDosVecesNoDuplicaNada() = runBlocking {
        val seeder = CatalogSeeder(context, database)
        seeder.seedIfNeeded()
        val second = seeder.seedIfNeeded()

        assertEquals("la segunda siembra no debe hacer nada", 0, second)
        assertEquals(6419, database.speciesDao().count())
    }

    // --- Politica de escritura de la coleccion -------------------------------

    private suspend fun newSession(startedAt: Long = 1_000L): Long =
        database.listeningSessionDao().insert(
            ListeningSessionEntity(startedAt = startedAt, modelId = MODEL),
        )

    private fun observation(sessionId: Long, speciesId: String, score: Float) = ObservationEntity(
        speciesId = speciesId,
        sessionId = sessionId,
        timestamp = 1_000L,
        source = ObservationSource.AUDIO,
        rawConfidence = score,
        adjustedConfidence = score,
        modelId = MODEL,
        rarityTierAtCapture = 0,
    )

    @Test
    fun unaEspeciePorSesionAunqueSeDetecteMuchasVeces() = runBlocking {
        val session = newSession()
        val dao = database.observationDao()

        // Simula una escucha larga: la misma ave detectada en 50 ventanas.
        repeat(50) { dao.recordBestInSession(observation(session, "turdus_merula", 0.7f)) }

        // Sin esta regla habria 50 filas y la coleccion no significaria nada.
        assertEquals(1, dao.pendingReview().first().size)
    }

    @Test
    fun conservaLaMejorPuntuacionDeLaSesionYNoLaUltima() = runBlocking {
        val session = newSession()
        val dao = database.observationDao()

        dao.recordBestInSession(observation(session, "turdus_merula", 0.60f))
        dao.recordBestInSession(observation(session, "turdus_merula", 0.95f))
        dao.recordBestInSession(observation(session, "turdus_merula", 0.71f))

        assertEquals(0.95f, dao.findInSession(session, "turdus_merula")!!.adjustedConfidence, 1e-6f)
    }

    @Test
    fun unaDeteccionMejorNoDeshaceLoQueElUsuarioYaDecidio() = runBlocking {
        val session = newSession()
        val dao = database.observationDao()

        val id = dao.recordBestInSession(observation(session, "turdus_merula", 0.60f))
        dao.reject(id)

        // Llega una deteccion mejor: debe actualizar la puntuacion pero NO
        // resucitar algo que el usuario ya habia descartado.
        dao.recordBestInSession(observation(session, "turdus_merula", 0.99f))

        val stored = dao.findInSession(session, "turdus_merula")!!
        assertEquals(0.99f, stored.adjustedConfidence, 1e-6f)
        assertTrue("el rechazo del usuario manda sobre el modelo", stored.userRejected)
    }

    @Test
    fun sesionesDistintasSonAvistamientosDistintos() = runBlocking {
        val dao = database.observationDao()
        val saturday = newSession()
        val sunday = newSession(startedAt = 90_000L)

        dao.recordBestInSession(observation(saturday, "turdus_merula", 0.8f))
        dao.recordBestInSession(observation(sunday, "turdus_merula", 0.8f))

        val first = dao.findInSession(saturday, "turdus_merula")
        val second = dao.findInSession(sunday, "turdus_merula")
        assertNotNull(first)
        assertNotNull(second)
        assertTrue("deben ser filas distintas", first!!.id != second!!.id)
    }

    @Test
    fun sePuedeVolverAtrasDeUnaDecision() = runBlocking {
        val session = newSession()
        val dao = database.observationDao()
        val id = dao.recordBestInSession(observation(session, "turdus_merula", 0.9f))

        dao.reject(id)
        assertEquals(0, dao.pendingReview().first().size)

        // Uno aprende a distinguir cantos con el tiempo: rechazar no puede ser
        // una condena perpetua.
        dao.clearReview(id)

        val pending = dao.pendingReview().first()
        assertEquals(1, pending.size)
        assertTrue(!pending[0].userRejected && !pending[0].userConfirmed)
    }

    @Test
    fun laColeccionSoloContieneLoConfirmado() = runBlocking {
        val session = newSession()
        val dao = database.observationDao()

        val confirmed = dao.recordBestInSession(observation(session, "turdus_merula", 0.9f))
        dao.recordBestInSession(observation(session, "erithacus_rubecula", 0.8f))
        dao.confirm(confirmed)

        val collection = dao.confirmed().first()
        assertEquals(1, collection.size)
        assertEquals("turdus_merula", collection[0].speciesId)

        val pending = dao.pendingReview().first()
        assertEquals(1, pending.size)
        assertEquals("erithacus_rubecula", pending[0].speciesId)
    }

    @Test
    fun descartaSesionesQueNoRegistraronNada() = runBlocking {
        val sessionDao = database.listeningSessionDao()
        val withFindings = newSession()
        val empty = sessionDao.insert(
            ListeningSessionEntity(startedAt = 2_000L, endedAt = 3_000L, modelId = MODEL),
        )
        sessionDao.close(withFindings, 5_000L, 100, 40)
        database.observationDao().recordBestInSession(observation(withFindings, "turdus_merula", 0.9f))

        val deleted = sessionDao.deleteEmptySessions()

        assertEquals(1, deleted)
        assertNotNull(sessionDao.findById(withFindings))
        assertNull(sessionDao.findById(empty))
    }

    private companion object {
        const val MODEL = "birdnet-v2.4"
    }
}
