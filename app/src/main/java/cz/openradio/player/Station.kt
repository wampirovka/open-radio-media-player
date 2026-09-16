package cz.openradio.player

data class Station(
    val id: String,
    val name: String,
    val streamUrl: String,
    val homepageUrl: String? = null,
    val logoUrl: String? = null
)

object DefaultStations {
    // Placeholder until the current official Fajn Rock stream endpoint is verified.
    // Keeping it configurable prevents the playback engine from depending on a guessed URL.
    val fajnRock = Station(
        id = "fajn-rock-music",
        name = "Fajn Rock Music",
        streamUrl = "https://example.invalid/fajn-rock-stream",
        homepageUrl = "https://www.fajnrockmusic.cz/"
    )
}
