package dev.pelayo.aves.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.pelayo.aves.core.database.dao.ChecklistDao
import dev.pelayo.aves.core.database.dao.ListeningSessionDao
import dev.pelayo.aves.core.database.dao.ModelLabelDao
import dev.pelayo.aves.core.database.dao.ObservationDao
import dev.pelayo.aves.core.database.dao.RegionDao
import dev.pelayo.aves.core.database.dao.SeasonalityDao
import dev.pelayo.aves.core.database.dao.SpeciesDao
import dev.pelayo.aves.core.database.entity.ListeningSessionEntity
import dev.pelayo.aves.core.database.entity.ModelLabelEntity
import dev.pelayo.aves.core.database.entity.ObservationCandidateEntity
import dev.pelayo.aves.core.database.entity.ObservationEntity
import dev.pelayo.aves.core.database.entity.PokedexPreferencesEntity
import dev.pelayo.aves.core.database.entity.RegionCellEntity
import dev.pelayo.aves.core.database.entity.RegionChecklistEntity
import dev.pelayo.aves.core.database.entity.RegionChecklistSpeciesEntity
import dev.pelayo.aves.core.database.entity.RegionMonthEntity
import dev.pelayo.aves.core.database.entity.SeasonalityEntity
import dev.pelayo.aves.core.database.entity.SpeciesEntity
import dev.pelayo.aves.core.database.entity.SpeciesFrequencyEntity
import dev.pelayo.aves.core.database.entity.SpeciesNameEntity

/**
 * MIGRACIONES: cada cambio de esquema sube [version] y anade una
 * `AutoMigration` (salvo 1 -> 2, que va a mano: ver `Migrations.kt`). Hay un
 * test que migra de cada version a la ultima (`MigrationTest`).
 * Nunca `fallbackToDestructiveMigration`: eso borraria la
 * coleccion del usuario, que es lo unico de esta base que no se puede
 * regenerar. Los esquemas exportados viven en `src/androidTest/assets`
 * y hay que versionarlos: sin el JSON de la version anterior Room no puede
 * generar la migracion.
 *
 * Historial:
 *  1. Esquema inicial.
 *  2. `species.searchName/summary/imageAttribution`, tabla
 *     `region_cell_months` e indice unico en los candidatos.
 *  3. Listas de especies por zona (`region_checklists`,
 *     `region_checklist_species`) y preferencias de la Pokedex.
 *  4. Zona elegida a mano (`pokedex_preferences.customZone*`).
 *  5. `species.taxonOrder`, para ordenar las familias como las guias.
 *  6. `species_seasonality`: cuando se ve cada especie alrededor de una celda.
 */
@Database(
    entities = [
        SpeciesEntity::class,
        SpeciesNameEntity::class,
        ModelLabelEntity::class,
        ListeningSessionEntity::class,
        ObservationEntity::class,
        ObservationCandidateEntity::class,
        RegionCellEntity::class,
        RegionMonthEntity::class,
        SpeciesFrequencyEntity::class,
        RegionChecklistEntity::class,
        RegionChecklistSpeciesEntity::class,
        PokedexPreferencesEntity::class,
        SeasonalityEntity::class,
    ],
    version = 6,
    exportSchema = true,
    // 1 -> 2 va a mano (ver MIGRATION_1_2): la automatica fallaba con duplicados.
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
    ],
)
@TypeConverters(Converters::class)
abstract class AvesDatabase : RoomDatabase() {

    abstract fun speciesDao(): SpeciesDao
    abstract fun modelLabelDao(): ModelLabelDao
    abstract fun listeningSessionDao(): ListeningSessionDao
    abstract fun observationDao(): ObservationDao
    abstract fun regionDao(): RegionDao
    abstract fun checklistDao(): ChecklistDao
    abstract fun seasonalityDao(): SeasonalityDao

    companion object {
        const val NAME = "aves.db"

        /**
         * La base se crea vacia y el catalogo lo importa [CatalogSeeder] en el
         * primer arranque.
         *
         * No se usa `createFromAsset()` con una base prepoblada, que era el plan
         * original, porque ese mecanismo exige que el fichero lleve el
         * identityHash exacto que Room deriva del esquema: un cambio en
         * cualquier entidad romperia el arranque en el movil del usuario en vez
         * de en el build. Importar ~30.000 filas cuesta unos segundos, una sola
         * vez y en segundo plano, y falla de forma depurable.
         */
        fun create(context: Context): AvesDatabase =
            Room.databaseBuilder(context.applicationContext, AvesDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()

        /** Las migraciones escritas a mano. Las demas las genera Room. */
        internal val MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}
