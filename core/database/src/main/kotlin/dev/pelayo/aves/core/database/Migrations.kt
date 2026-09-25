package dev.pelayo.aves.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * La unica migracion escrita a mano, y por que.
 *
 * La version 1 anadia los candidatos de cada deteccion sin reemplazar los de la
 * anterior, y no tenia indice unico: una especie detectada varias veces en una
 * sesion dejaba filas repetidas con el mismo (observacion, puesto). La version
 * 2 crea ese indice UNICO, y con la `AutoMigration` generada la creacion
 * fallaba sobre los duplicados: la app no abria la base, en cada arranque.
 *
 * Es la misma SQL que genera Room para 1 -> 2 (copiada de
 * `AvesDatabase_AutoMigration_1_2_Impl`), con un unico cambio: al copiar los
 * candidatos a la tabla nueva se queda solo el mas reciente de cada
 * (observacion, puesto), que es el que habia dejado la ultima deteccion.
 * `MigrationTest` la comprueba con una base v1 con
 * duplicados.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `region_cell_months` (`cellId` TEXT NOT NULL, `month` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, `totalObservations` INTEGER NOT NULL, PRIMARY KEY(`cellId`, `month`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `_new_species` (`speciesId` TEXT NOT NULL, `scientificName` TEXT NOT NULL, `genus` TEXT NOT NULL, `family` TEXT NOT NULL, `order` TEXT NOT NULL, `defaultCommonName` TEXT NOT NULL, `ebirdCode` TEXT, `gbifSpeciesKey` INTEGER, `imageUrl` TEXT, `wikipediaUrl` TEXT, `searchName` TEXT NOT NULL DEFAULT '', `summary` TEXT, `imageAttribution` TEXT, PRIMARY KEY(`speciesId`))")
        db.execSQL("INSERT INTO `_new_species` (`speciesId`,`scientificName`,`genus`,`family`,`order`,`defaultCommonName`,`ebirdCode`,`gbifSpeciesKey`,`imageUrl`,`wikipediaUrl`) SELECT `speciesId`,`scientificName`,`genus`,`family`,`order`,`defaultCommonName`,`ebirdCode`,`gbifSpeciesKey`,`imageUrl`,`wikipediaUrl` FROM `species`")
        db.execSQL("DROP TABLE `species`")
        db.execSQL("ALTER TABLE `_new_species` RENAME TO `species`")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_species_scientificName` ON `species` (`scientificName`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_species_ebirdCode` ON `species` (`ebirdCode`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_species_family` ON `species` (`family`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_species_searchName` ON `species` (`searchName`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `_new_observation_candidates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `observationId` INTEGER NOT NULL, `speciesId` TEXT NOT NULL, `rank` INTEGER NOT NULL, `score` REAL NOT NULL, FOREIGN KEY(`observationId`) REFERENCES `observations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        // El cambio respecto a lo generado: un candidato por (observacion, puesto).
        db.execSQL(
            "INSERT INTO `_new_observation_candidates` (`id`,`observationId`,`speciesId`,`rank`,`score`) " +
                "SELECT `id`,`observationId`,`speciesId`,`rank`,`score` FROM `observation_candidates` " +
                "WHERE `id` IN (SELECT MAX(`id`) FROM `observation_candidates` GROUP BY `observationId`, `rank`)",
        )
        db.execSQL("DROP TABLE `observation_candidates`")
        db.execSQL("ALTER TABLE `_new_observation_candidates` RENAME TO `observation_candidates`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_observation_candidates_observationId` ON `observation_candidates` (`observationId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_observation_candidates_observationId_rank` ON `observation_candidates` (`observationId`, `rank`)")
    }
}
