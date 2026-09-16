package cz.openradio.player

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

object RadioBrowserApi {
    private const val BASE_URL = "https://de1.api.radio-browser.info"
    private const val USER_AGENT = "OpenRadioMediaPlayer/0.1"

    fun loadCzechStations(limit: Int = 50): List<Station> =
        requestStations("countrycode=CZ&hidebroken=true&order=votes&reverse=true&limit=$limit")

    fun searchStations(query: String, limit: Int = 50): List<Station> {
        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        return requestStations("name=$encoded&hidebroken=true&order=votes&reverse=true&limit=$limit")
    }

    fun registerClick(stationUuid: String) {
        Thread {
            try {
                get("$BASE_URL/json/url/${URLEncoder.encode(stationUuid, Charsets.UTF_8.name())}")
            } catch (_: Exception) {
                // Tracking a click must never prevent playback.
            }
        }.start()
    }

    private fun requestStations(query: String): List<Station> {
        val json = get("$BASE_URL/json/stations/search?$query")
        val array = JSONArray(json)
        val result = mutableListOf<Station>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val uuid = item.optString("stationuuid").takeIf { it.isNotBlank() } ?: continue
            val name = item.optString("name").trim().takeIf { it.isNotBlank() } ?: continue
            val streamUrl = item.optString("url_resolved")
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: item.optString("url").takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: continue

            result += Station(
                id = uuid,
                name = name,
                streamUrl = streamUrl,
                homepageUrl = item.optString("homepage").takeIf { it.isNotBlank() },
                logoUrl = item.optString("favicon").takeIf { it.isNotBlank() },
                votes = item.optInt("votes", 0),
                listeners = item.optInt("clickcount", 0)
            )
        }

        return result
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<Station> { it.votes }
                    .thenByDescending { it.listeners }
                    .thenBy { it.name.lowercase() }
            )
    }

    private fun get(urlString: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }

        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
