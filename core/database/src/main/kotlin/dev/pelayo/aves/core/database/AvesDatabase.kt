package dev.pelayo.aves.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.pelayo.aves.core.database.dao.ListeningSessionDao
import dev.pelayo.aves.core.database.dao.ModelLabelDao
import dev.pelayo.aves.core.database.dao.ObservationDao
import dev.pelayo.aves.core.database.dao.SpeciesDao
import dev.pelayo.aves.core.database.entity.ListeningSessionEntity
import dev.pelayo.aves.core.database.entity.ModelLabelEntity
import dev.pelayo.aves.core.database.entity.ObservationCandidateEntity
import dev.pelayo.aves.core.database.entity.ObservationEntity
import dev.pelayo.aves.core.database.entity.RegionCellEntity
import dev.pelayo.aves.core.database.entity.SpeciesEntity
import dev.pelayo.aves.core.database.entity.SpeciesFrequencyEntity
import dev.pelayo.aves.core.database.entity.SpeciesNameEntity

@Database(
    entities = [
        SpeciesEntity::class,
        SpeciesNameEntity::class,
        ModelLabelEntity::class,
        ListeningSessionEntity::class,
        ObservationEntity::class,
        ObservationCandidateEntity::class,
        RegionCellEntity::class,
        SpeciesFrequencyEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AvesDatabase : RoomDatabase() {

    abstract fun speciesDao(): SpeciesDao
    abstract fun modelLabelDao(): ModelLabelDao
    abstract fun listeningSessionDao(): ListeningSessionDao
    abstract fun observationDao(): ObservationDao

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
         * de en el build. Importar ~13.000 filas cuesta ~2,3 s medidos en
         * emulador, una sola vez y en segundo plano, y falla de forma
         * depurable.
         */
        fun create(context: Context): AvesDatabase =
            Room.databaseBuilder(context.applicationContext, AvesDatabase::class.java, NAME)
                .build()
    }
}
