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
        val custom = parseCustomStation(query)
        if (custom != null) return listOf(custom)

        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        return requestStations("name=$encoded&hidebroken=true&order=votes&reverse=true&limit=$limit")
    }

    /**
     * Supports adding a custom stream directly from the existing search field.
     * Format: Name | https://example.com/stream
     * A plain stream URL is also accepted and gets its host used as the station name.
     */
    private fun parseCustomStation(query: String): Station? {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return null

        val separator = trimmed.indexOf('|')
        val name: String
        val streamUrl: String

        if (separator > 0) {
            name = trimmed.substring(0, separator).trim()
            streamUrl = trimmed.substring(separator + 1).trim()
        } else if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            streamUrl = trimmed
            name = try {
                URL(streamUrl).host.removePrefix("www.").ifBlank { "Vlastní rádio" }
            } catch (_: Exception) {
                "Vlastní rádio"
            }
        } else {
            return null
        }

        if (name.isBlank()) return null
        if (!streamUrl.startsWith("http://") && !streamUrl.startsWith("https://")) return null

        val id = "custom:" + streamUrl.hashCode().toUInt().toString(16)
        return Station(
            id = id,
            name = name,
            streamUrl = streamUrl,
            homepageUrl = null,
            logoUrl = null,
            votes = 0,
            listeners = 0
        )
    }

    fun registerClick(stationUuid: String) {
        if (stationUuid.startsWith("custom:")) return

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
