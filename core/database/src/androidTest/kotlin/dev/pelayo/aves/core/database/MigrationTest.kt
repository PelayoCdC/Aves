package dev.pelayo.aves.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cada version publicada tiene que poder llegar a la ultima sin perder la
 * coleccion. Es lo unico de la base que no se regenera: el catalogo se vuelve
 * a sembrar y las caches de GBIF se vuelven a pedir, pero lo que el usuario oyo
 * y confirmo solo esta aqui.
 *
 * Usa los esquemas exportados en `src/androidTest/assets`.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AvesDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `cada version migra hasta la ultima`() {
        for (version in 1 until LATEST) {
            val name = "migracion-desde-$version.db"
            helper.createDatabase(name, version).close()
            helper.runMigrationsAndValidate(name, LATEST, true, *AvesDatabase.MIGRATIONS).close()
        }
    }

    @Test
    fun `una base v1 con candidatos repetidos migra y conserva uno por puesto`() {
        // Lo que dejaba la v1: la misma especie detectada varias veces en una
        // sesion anadia sus candidatos otra vez. La v2 crea un indice unico
        // (observacion, puesto) y con la migracion automatica esto no abria.
        helper.createDatabase(DB, 1).apply {
            execSQL(
                "INSERT INTO listening_sessions (id, startedAt, windowsProcessed, windowsSkipped, modelId) " +
                    "VALUES (1, 1000, 10, 0, 'birdnet-v2.4')",
            )
            execSQL(
                "INSERT INTO observations (id, speciesId, sessionId, timestamp, source, rawConfidence, " +
                    "adjustedConfidence, modelId, userConfirmed, userRejected, rarityTierAtCapture) " +
                    "VALUES (1, 'turdus_merula', 1, 2000, 'AUDIO', 0.8, 0.8, 'birdnet-v2.4', 1, 0, -1)",
            )
            // Puesto 0 dos veces y puesto 1 una: deben quedar dos filas, y la
            // del puesto 0 la mas reciente (id 3).
            execSQL("INSERT INTO observation_candidates (id, observationId, speciesId, rank, score) VALUES (1, 1, 'turdus_philomelos', 0, 0.2)")
            execSQL("INSERT INTO observation_candidates (id, observationId, speciesId, rank, score) VALUES (2, 1, 'erithacus_rubecula', 1, 0.1)")
            execSQL("INSERT INTO observation_candidates (id, observationId, speciesId, rank, score) VALUES (3, 1, 'turdus_viscivorus', 0, 0.3)")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(DB, LATEST, true, *AvesDatabase.MIGRATIONS)

        migrated.query("SELECT id, speciesId FROM observation_candidates ORDER BY rank").use { cursor ->
            assertEquals(2, cursor.count)
            cursor.moveToFirst()
            assertEquals(3L, cursor.getLong(0))
            assertEquals("turdus_viscivorus", cursor.getString(1))
        }
        // La observacion, que es lo que importa, sigue ahi y confirmada.
        migrated.query("SELECT userConfirmed FROM observations WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        migrated.close()
    }

    private companion object {
        const val DB = "migracion.db"
        const val LATEST = 6
    }
}
