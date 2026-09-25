package dev.pelayo.aves.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.pelayo.aves.core.database.entity.SeasonalityEntity

@Dao
interface SeasonalityDao {

    @Query("SELECT * FROM species_seasonality WHERE speciesId = :speciesId AND cellId = :cellId")
    suspend fun find(speciesId: String, cellId: String): SeasonalityEntity?

    @Upsert
    suspend fun upsert(row: SeasonalityEntity)
}
