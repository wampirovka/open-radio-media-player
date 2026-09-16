package cz.openradio.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class StationRepository(private val dao: StationDao) {
    val stations: Flow<List<Station>> =
        dao.observeAll().map { entities -> entities.map(StationEntity::toStation) }

    val favorites: Flow<List<Station>> =
        dao.observeFavorites().map { entities -> entities.map(StationEntity::toStation) }

    fun search(query: String): Flow<List<Station>> =
        dao.observeSearch(query).map { entities -> entities.map(StationEntity::toStation) }

    suspend fun syncStations(stations: List<Station>) {
        if (stations.isEmpty()) return

        val existingFavorites = stations.associate { station ->
            station.id to (dao.isFavorite(station.id) == true)
        }

        dao.insertAll(
            stations.map { station ->
                station.toEntity(isFavorite = existingFavorites[station.id] == true)
            }
        )
    }

    suspend fun toggleFavorite(station: Station) {
        val current = dao.isFavorite(station.id) == true
        if (current) {
            dao.setFavorite(station.id, false)
        } else {
            dao.insertAll(listOf(station.toEntity(isFavorite = true)))
        }
    }

    suspend fun setFavorite(station: Station, favorite: Boolean) {
        if (dao.isFavorite(station.id) == null) {
            dao.insertAll(listOf(station.toEntity(isFavorite = favorite)))
        } else {
            dao.setFavorite(station.id, favorite)
        }
    }
}
