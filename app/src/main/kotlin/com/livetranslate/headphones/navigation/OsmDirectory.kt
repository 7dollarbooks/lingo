package com.livetranslate.headphones.navigation

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Public OpenStreetMap services. One geocode, then one route. GPS points are not streamed.
 * This does not use the Gemini home-Wi-Fi gate.
 */
class OsmDirectory(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    fun geocode(query: String): LatLng? {
        val q = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        if (q.isEmpty()) return null
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=$q"
        val body = get(url) ?: return null
        val list = Json.parse(body) as? List<*> ?: return null
        val first = list.firstOrNull() as? Map<*, *> ?: return null
        val lat = (first["lat"] as? String)?.toDoubleOrNull() ?: return null
        val lon = (first["lon"] as? String)?.toDoubleOrNull() ?: return null
        return LatLng(lat, lon)
    }

    fun route(baseUrl: String, from: LatLng, to: LatLng, mode: TravelMode): OsrmRoute? {
        val root = baseUrl.trim().trimEnd('/')
        if (root.isEmpty()) return null
        val json = """{"locations":[{"lat":${from.lat},"lon":${from.lon}},{"lat":${to.lat},"lon":${to.lon}}],"costing":"${mode.costing}","directions_options":{"units":"kilometers"}}"""
        val body = post("$root/route", json) ?: return null
        return ValhallaRoute.parse(body)
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        return execute(request)
    }

    private fun post(url: String, json: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .post(json.toRequestBody(JSON))
            .build()
        return execute(request)
    }

    private fun execute(request: Request): String? =
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull()

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val USER_AGENT = "Lingo/1.0 (Android; personal navigation)"
    }
}
