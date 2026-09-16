package cz.openradio.player

data class Station(
    val id: String,
    val name: String,
    val streamUrl: String,
    val homepageUrl: String? = null,
    val logoUrl: String? = null
)

object DefaultStations {
    val fajnRock = Station(
        id = "fajn-rock-music",
        name = "Fajn Rock Music",
        streamUrl = "https://icecast1.play.cz/fajnrock128.mp3?1397678024622.mp3&r=375",
        homepageUrl = "https://www.fajnrockmusic.cz/"
    )
}
