package dev.pelayo.aves.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pelayo.aves.core.database.entity.ListeningSessionEntity
import dev.pelayo.aves.core.database.entity.ObservationCandidateEntity
import dev.pelayo.aves.core.database.entity.ObservationEntity
import dev.pelayo.aves.core.model.ObservationSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun siembraLaTaxonomiaEnteraDesdeLosAssets() = runBlocking {
        val report = CatalogSeeder(context, database).seedIfNeeded()

        // La taxonomia de eBird entera, no las etiquetas de un modelo: asi cada
        // modelo es solo una tabla de mapeo mas.
        assertTrue("al menos 11.000 especies", report.speciesImported >= 11_000)
        assertEquals(report.speciesImported, database.speciesDao().count())
    }

    @Test
    fun siembraElCrossWalkDeCadaModeloQueHayaEnAssets() = runBlocking {
        val report = CatalogSeeder(context, database).seedIfNeeded()
        val labels = database.modelLabelDao()

        // El de BirdNET v2.4 siempre esta; los otros dos dependen de que se
        // haya ejecutado build-catalog.ps1 con sus etiquetas presentes.
        assertTrue(CatalogSeeder.BIRDNET_MODEL_ID in report.modelsImported)
        assertEquals(6522, labels.countFor(CatalogSeeder.BIRDNET_MODEL_ID))
        if (CatalogSeeder.AIY_MODEL_ID in report.modelsImported) {
            assertEquals(965, labels.countFor(CatalogSeeder.AIY_MODEL_ID))
        }
        if (CatalogSeeder.BIRDNET_PLUS_MODEL_ID in report.modelsImported) {
            assertEquals(9789, labels.countFor(CatalogSeeder.BIRDNET_PLUS_MODEL_ID))
            // Las 866 clases sin especie son anfibios, mamiferos e insectos.
            assertEquals(866, labels.unmappedCountFor(CatalogSeeder.BIRDNET_PLUS_MODEL_ID))
        }
    }

    @Test
    fun elCrossWalkDejaSinMapearSoloLoQueNoEsUnAve() = runBlocking {
        CatalogSeeder(context, database).seedIfNeeded()

        // 11 clases de no-evento (perro, motor, sirena, disparo, ruido, ambiente,
        // fuegos artificiales, herramientas y las tres de humano) mas 90 animales
        // que BirdNET incluye pero no son aves: ranas, sapos, grillos, katidos,
        // un ciervo y algun mono. Ni una sola ave sin mapear.
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
        // La columna de busqueda va sin tildes ni mayusculas.
        assertTrue(petirrojo.searchName.contains("petirrojo europeo"))
        assertFalse(petirrojo.searchName.any { it.isUpperCase() })
    }

    @Test
    fun buscaSinTildesNiMayusculas() = runBlocking {
        CatalogSeeder(context, database).seedIfNeeded()

        // Nadie pone tildes con el movil en una mano y los prismaticos en la otra.
        val hits = database.speciesDao().search("carabo comun").first()
        assertTrue(hits.any { it.speciesId == "strix_aluco" })
    }

    @Test
    fun recuperaLasAvesCuyoCodigoEbirdCaduco() = runBlocking {
        CatalogSeeder(context, database).seedIfNeeded()

        // La gaviota argentea es una de las clases cuyo codigo eBird de 2023
        // ya no existe: solo se mapea gracias al respaldo por nombre cientifico.
        assertNotNull(database.speciesDao().findById("larus_argentatus"))

        val labels = database.modelLabelDao().labelsFor(CatalogSeeder.BIRDNET_MODEL_ID)
        // El carbonero japones hubo que auditarlo a mano: eBird lo agrupo
        // dentro de Parus major.
        val parusMinor = labels.first { it.rawLabel.startsWith("Parus minor_") }
        assertEquals("parus_major", parusMinor.speciesId)
        // Y la garcilla bueyera cambio de genero (Bubulcus -> Ardea).
        val garcilla = labels.first { it.rawLabel.startsWith("Bubulcus ibis_") }
        assertEquals("ardea_ibis", garcilla.speciesId)
    }

    @Test
    fun sembrarDosVecesNoDuplicaNada() = runBlocking {
        val seeder = CatalogSeeder(context, database)
        val first = seeder.seedIfNeeded()
        val second = seeder.seedIfNeeded()

        assertFalse("la segunda siembra no debe hacer nada", second.didAnything)
        assertEquals(first.speciesImported, database.speciesDao().count())
    }

    @Test
    fun elTotalDeLaPokedexSonLasEspeciesQueAlgunModeloDetecta() = runBlocking {
        CatalogSeeder(context, database).seedIfNeeded()

        val detectable = database.modelLabelDao().detectableSpeciesCount().first()
        // Mas que las 6.419 de BirdNET v2.4 (el de imagen anade algunas) y menos
        // que el catalogo entero, que nadie promete completar.
        assertTrue(detectable >= 6_419)
        assertTrue(detectable < database.speciesDao().count())
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
    fun confirmarComoOtraEspecieRenombraLaObservacion() = runBlocking {
        val session = newSession()
        val dao = database.observationDao()
        val id = dao.recordBestInSession(observation(session, "turdus_merula", 0.9f))

        // "No era un mirlo, era un zorzal."
        val confirmed = dao.confirmAs(id, "turdus_philomelos")

        assertEquals(id, confirmed)
        val stored = dao.findById(id)!!
        assertEquals("turdus_philomelos", stored.speciesId)
        assertTrue(stored.userConfirmed)
    }

    @Test
    fun confirmarComoUnaEspecieQueYaEstabaEnLaSesionNoDuplica() = runBlocking {
        val session = newSession()
        val dao = database.observationDao()
        val mirlo = dao.recordBestInSession(observation(session, "turdus_merula", 0.9f))
        val zorzal = dao.recordBestInSession(observation(session, "turdus_philomelos", 0.4f))

        // El zorzal ya tenia fila: renombrar el mirlo violaria el indice unico.
        // Lo que el usuario quiso decir es "el zorzal si, el mirlo no".
        val confirmed = dao.confirmAs(mirlo, "turdus_philomelos")

        assertEquals(zorzal, confirmed)
        assertTrue(dao.findById(zorzal)!!.userConfirmed)
        assertTrue(dao.findById(mirlo)!!.userRejected)
    }

    @Test
    fun losCandidatosSeSustituyenEnterosYNoSeAcumulan() = runBlocking {
        val session = newSession()
        val dao = database.observationDao()
        val id = dao.recordBestInSession(observation(session, "turdus_merula", 0.9f))

        dao.replaceCandidates(
            id,
            listOf(
                ObservationCandidateEntity(observationId = id, speciesId = "turdus_philomelos", rank = 0, score = 0.3f),
                ObservationCandidateEntity(observationId = id, speciesId = "turdus_iliacus", rank = 1, score = 0.1f),
            ),
        )
        dao.replaceCandidates(
            id,
            listOf(
                ObservationCandidateEntity(observationId = id, speciesId = "turdus_viscivorus", rank = 0, score = 0.2f),
            ),
        )

        val candidates = dao.candidatesFor(id)
        assertEquals(1, candidates.size)
        assertEquals("turdus_viscivorus", candidates[0].speciesId)
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

    @Test
    fun elHistorialCuentaLoQueProdujoCadaSesion() = runBlocking {
        val sessionDao = database.listeningSessionDao()
        val dao = database.observationDao()
        val session = newSession()
        val mirlo = dao.recordBestInSession(observation(session, "turdus_merula", 0.9f))
        dao.recordBestInSession(observation(session, "erithacus_rubecula", 0.8f))
        val rejected = dao.recordBestInSession(observation(session, "parus_major", 0.5f))
        dao.confirm(mirlo)
        dao.reject(rejected)

        val row = sessionDao.recentWithCounts().first().single()

        assertEquals(2, row.speciesCount)
        assertEquals(1, row.confirmedCount)
        assertEquals(1, row.pendingCount)
    }

    private companion object {
        const val MODEL = "birdnet-v2.4"
    }
}
