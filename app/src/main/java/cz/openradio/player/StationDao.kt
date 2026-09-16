package cz.openradio.player

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StationDao {
    @Query("SELECT * FROM stations ORDER BY votes DESC, listeners DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE isFavorite = 1 ORDER BY name COLLATE NOCASE ASC")
    fun observeFavorites(): Flow<List<StationEntity>>

    @Query("SELECT isFavorite FROM stations WHERE id = :stationId LIMIT 1")
    suspend fun isFavorite(stationId: String): Boolean?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stations: List<StationEntity>)

    @Query("UPDATE stations SET isFavorite = :favorite WHERE id = :stationId")
    suspend fun setFavorite(stationId: String, favorite: Boolean)

    @Query("DELETE FROM stations WHERE id = :stationId")
    suspend fun deleteById(stationId: String)
}
