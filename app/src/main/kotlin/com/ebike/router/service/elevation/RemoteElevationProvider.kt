package com.ebike.router.service.elevation

import com.ebike.router.model.GeoPoint
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Tier 3: Open-Meteo's free elevation API (Copernicus 30m/90m DEM, no API
 * key, ~10k calls/day). Requests are batched (GET, comma-separated
 * lat/lng lists) to keep payloads small and stay within the plan's request
 * budget for a multi-kilometer route.
 */
class RemoteElevationProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) : ElevationProvider {

    override suspend fun getElevations(points: List<GeoPoint>): List<Double?> = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext emptyList()

        val results = MutableList<Double?>(points.size) { null }
        var offset = 0
        for (chunk in points.chunked(BATCH_SIZE)) {
            fetchChunk(chunk).forEachIndexed { i, ele -> results[offset + i] = ele }
            offset += chunk.size
        }
        results
    }

    private fun fetchChunk(chunk: List<GeoPoint>, retriesLeft: Int = 1): List<Double?> {
        val lat = chunk.joinToString(",") { it.lat.toString() }
        val lng = chunk.joinToString(",") { it.lng.toString() }
        val url = "$BASE_URL?latitude=$lat&longitude=$lng"

        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "EBikeRouterAndroid/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body?.string() ?: throw IOException("empty body")
                val obj = gson.fromJson(body, JsonObject::class.java)
                val elevArray = obj?.getAsJsonArray("elevation")
                    ?: return List(chunk.size) { null }
                (0 until elevArray.size()).map { elevArray.get(it).asDouble }
            }
        } catch (e: Exception) {
            if (retriesLeft > 0) fetchChunk(chunk, retriesLeft - 1) else List(chunk.size) { null }
        }
    }

    companion object {
        private const val BASE_URL = "https://api.open-meteo.com/v1/elevation"
        private const val BATCH_SIZE = 100
    }
}
