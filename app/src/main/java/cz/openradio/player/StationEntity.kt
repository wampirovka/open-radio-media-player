package cz.openradio.player

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stations")
data class StationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val streamUrl: String,
    val homepageUrl: String?,
    val logoUrl: String?,
    val votes: Int,
    val listeners: Int,
    val isFavorite: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

fun StationEntity.toStation(): Station = Station(
    id = id,
    name = name,
    streamUrl = streamUrl,
    homepageUrl = homepageUrl,
    logoUrl = logoUrl,
    votes = votes,
    listeners = listeners,
    isFavorite = isFavorite
)

fun Station.toEntity(isFavorite: Boolean = this.isFavorite): StationEntity = StationEntity(
    id = id,
    name = name,
    streamUrl = streamUrl,
    homepageUrl = homepageUrl,
    logoUrl = logoUrl,
    votes = votes,
    listeners = listeners,
    isFavorite = isFavorite
)
