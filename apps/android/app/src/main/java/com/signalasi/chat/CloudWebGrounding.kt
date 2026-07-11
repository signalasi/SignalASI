package com.signalasi.chat

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

object CloudWebGrounding {
    private const val TAG = "CloudWebGrounding"
    private const val MAX_CONTEXT_CHARS = 6_000
    private val liveTerms = listOf(
        "weather", "forecast", "temperature", "news", "latest", "today", "current",
        "price", "score", "schedule", "traffic", "exchange rate", "stock", "breaking",
        "realtime", "real-time", "now", "recent",
        "\u5929\u6c14", "\u6c14\u6e29", "\u9884\u62a5", "\u65b0\u95fb", "\u6700\u65b0", "\u4eca\u5929", "\u5f53\u524d", "\u73b0\u5728", "\u4ef7\u683c", "\u80a1\u4ef7", "\u6c47\u7387", "\u6bd4\u5206", "\u65e5\u7a0b", "\u8def\u51b5"
    )
    private val weatherTerms = listOf("weather", "forecast", "temperature", "\u5929\u6c14", "\u6c14\u6e29", "\u9884\u62a5")

    fun enrich(turns: List<ChatMessage>): List<ChatMessage> {
        val query = turns.lastOrNull { it.isMine && it.content.isNotBlank() }?.content?.trim().orEmpty()
        if (!needsLiveWeb(query)) return turns
        val context = runCatching {
            if (weatherTerms.any { query.contains(it, ignoreCase = true) }) {
                weatherContext(query).ifBlank { searchContext(query) }
            } else {
                searchContext(query)
            }
        }.onFailure { Log.w(TAG, "Live grounding failed for query=${query.take(80)}", it) }
            .getOrDefault("")
        Log.i(TAG, "Live grounding query=${query.take(80)} contextChars=${context.length}")
        if (context.isBlank()) return turns
        val enriched = turns.toMutableList()
        val index = enriched.indexOfLast { it.isMine && it.content.isNotBlank() }
        if (index < 0) return turns
        val original = enriched[index]
        enriched[index] = original.copy(content = buildString {
            append(original.content)
            append("\n\n[LIVE WEB CONTEXT - retrieved ")
            append(LocalDate.now())
            append("]\n")
            append(context.take(MAX_CONTEXT_CHARS))
            append("\n\nUse the live context when relevant. Cite the listed source URLs. Clearly state when the context is insufficient or conflicting.")
        })
        return enriched
    }

    private fun needsLiveWeb(query: String): Boolean =
        query.isNotBlank() && liveTerms.any { query.contains(it, ignoreCase = true) }

    private fun weatherContext(query: String): String {
        val requestedLocation = extractWeatherLocation(query)
        if (requestedLocation.isNotBlank()) {
            val language = if (requestedLocation.any { it.code in 0x3400..0x9fff }) "zh" else "en"
            return weatherForLocation(requestedLocation, language)
        }
        val place = geolocateByNetwork() ?: return ""
        return forecastForPlace(place)
    }

    private fun weatherForLocation(location: String, language: String): String {
        if (location.isBlank()) return "Location is required."
        val normalizedLanguage = if (language.equals("zh", ignoreCase = true)) "zh" else "en"
        val place = geocode(location, normalizedLanguage) ?: return "No matching location found for: $location"
        return forecastForPlace(place)
    }

    private fun forecastForPlace(place: JSONObject): String {
        val latitude = place.optDouble("latitude", Double.NaN)
        val longitude = place.optDouble("longitude", Double.NaN)
        if (latitude.isNaN() || longitude.isNaN()) return ""
        val forecastUrl = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$latitude&longitude=$longitude&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&forecast_days=3&timezone=auto"
        val forecast = JSONObject(get(forecastUrl))
        val current = forecast.optJSONObject("current") ?: JSONObject()
        val daily = forecast.optJSONObject("daily") ?: JSONObject()
        val displayName = listOf(place.optString("name"), place.optString("admin1"), place.optString("country"))
            .filter { it.isNotBlank() }.distinct().joinToString(", ")
        return buildString {
            append("Location: $displayName\n")
            append("Current: ${current.optDouble("temperature_2m")} C; feels like ${current.optDouble("apparent_temperature")} C; ")
            append("humidity ${current.optInt("relative_humidity_2m")}% ; precipitation ${current.optDouble("precipitation")} mm; ")
            append("wind ${current.optDouble("wind_speed_10m")} km/h; weather code ${current.optInt("weather_code")}.\n")
            append("Dates: ${daily.optJSONArray("time")}\n")
            append("High C: ${daily.optJSONArray("temperature_2m_max")}\n")
            append("Low C: ${daily.optJSONArray("temperature_2m_min")}\n")
            append("Max precipitation probability %: ${daily.optJSONArray("precipitation_probability_max")}\n")
            append("Source: $forecastUrl")
        }
    }

    private fun geocode(location: String, language: String? = null): JSONObject? {
        if (location.isBlank()) return null
        val selectedLanguage = language ?: if (location.any { it.code in 0x3400..0x9fff }) "zh" else "en"
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=$selectedLanguage&format=json&name=" + encode(location)
        return JSONObject(get(url)).optJSONArray("results")?.optJSONObject(0)
    }

    private fun geolocateByNetwork(): JSONObject? {
        val location = JSONObject(get("https://ipwho.is/"))
        if (!location.optBoolean("success", true)) return null
        val latitude = location.optDouble("latitude", Double.NaN)
        val longitude = location.optDouble("longitude", Double.NaN)
        if (latitude.isNaN() || longitude.isNaN()) return null
        return JSONObject()
            .put("name", location.optString("city"))
            .put("admin1", location.optString("region"))
            .put("country", location.optString("country"))
            .put("latitude", latitude)
            .put("longitude", longitude)
    }

    private fun extractWeatherLocation(query: String): String {
        val cleaned = query
            .replace(Regex("(?i)what(?:'s| is)?|how(?:'s| is)?|the|weather|forecast|temperature|today|now|current|in|at|for"), " ")
            .replace(Regex("[?.,!\\u3002\\uff0c\\uff1f\\uff01]"), " ")
            .replace("\u4eca\u5929", " ").replace("\u73b0\u5728", " ").replace("\u5f53\u524d", " ")
            .replace("\u7684", " ").replace("\u5929\u6c14", " ").replace("\u6c14\u6e29", " ").replace("\u9884\u62a5", " ")
            .replace("\u60c5\u51b5", " ").replace("\u600e\u4e48\u6837", " ").replace("\u5982\u4f55", " ").replace("\u600e\u6837", " ")
            .replace(Regex("\\s+"), " ").trim()
        return cleaned.take(100)
    }

    private fun searchContext(query: String): String {
        val html = get("https://html.duckduckgo.com/html/?q=${encode(query)}")
        val links = Regex("<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.IGNORE_CASE)
            .findAll(html).take(5).toList()
        if (links.isEmpty()) return ""
        return links.mapIndexed { index, match ->
            val url = decodeHtml(match.groupValues[1])
            val title = stripHtml(match.groupValues[2])
            val tail = html.substring(match.range.last + 1, minOf(html.length, match.range.last + 1 + 1800))
            val snippet = Regex("class=\"result__snippet\"[^>]*>(.*?)</", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(tail)?.groupValues?.get(1)?.let(::stripHtml).orEmpty()
            "${index + 1}. $title\n$snippet\nSource: $url"
        }.joinToString("\n\n")
    }

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("User-Agent", "SignalASI/0.1 Android")
            setRequestProperty("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
        }
        return try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { BufferedReader(it.reader(Charsets.UTF_8)).use { reader -> reader.readText() } }.orEmpty()
            if (connection.responseCode !in 200..299) error("Web grounding HTTP ${connection.responseCode}")
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun stripHtml(value: String): String = decodeHtml(value.replace(Regex("<[^>]+>"), " "))
        .replace(Regex("\\s+"), " ").trim()
    private fun decodeHtml(value: String): String = value
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")

    fun openAiTools(): JSONArray = JSONArray()
        .put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "get_weather")
                .put("description", "Get live current weather and a 3-day forecast for a named location.")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("location", JSONObject().put("type", "string").put("description", "City or place name"))
                        .put("language", JSONObject().put("type", "string").put("enum", JSONArray().put("en").put("zh")))
                    )
                    .put("required", JSONArray().put("location").put("language"))
                )
            )
        )
        .put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "web_search")
                .put("description", "Search the live web for recent or changing information and return sources.")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("query", JSONObject().put("type", "string"))
                        .put("language", JSONObject().put("type", "string").put("enum", JSONArray().put("en").put("zh")))
                    )
                    .put("required", JSONArray().put("query").put("language"))
                )
            )
        )

    fun executeTool(name: String, arguments: JSONObject): String = runCatching {
        when (name) {
            "get_weather" -> weatherForLocation(
                arguments.optString("location"),
                arguments.optString("language", "en")
            )
            "web_search" -> searchContext(arguments.optString("query"))
            else -> "Unknown tool: $name"
        }.ifBlank { "No live data was returned by $name." }
    }.onFailure { Log.w(TAG, "Tool execution failed name=$name", it) }
        .getOrElse { "Tool $name failed: ${it.message.orEmpty().take(200)}" }
}
