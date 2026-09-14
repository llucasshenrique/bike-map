package com.ebike.router.service

import com.ebike.router.model.GeoPoint
import com.ebike.router.model.PlaceCategory
import com.ebike.router.model.SearchResultItem
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class GeocodingService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun searchPlaces(
        query: String,
        userLat: Double? = null,
        userLng: Double? = null
    ): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()

        val results = mutableListOf<SearchResultItem>()

        // 1. Photon (Komoot OSM)
        try {
            var url = "https://photon.komoot.io/api/?q=${URLEncoder.encode(q, "UTF-8")}&limit=6"
            if (userLat != null && userLng != null) {
                url += "&lat=$userLat&lon=$userLng"
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "EBikeRouterAndroid/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val root = gson.fromJson(body, JsonObject::class.java)
                    val features = root.getAsJsonArray("features")
                    if (features != null) {
                        for (i in 0 until features.size()) {
                            val feat = features.get(i).asJsonObject
                            val props = feat.getAsJsonObject("properties")
                            val geom = feat.getAsJsonObject("geometry")
                            val coords = geom?.getAsJsonArray("coordinates")

                            if (coords != null && coords.size() >= 2) {
                                val lng = coords.get(0).asDouble
                                val lat = coords.get(1).asDouble

                                val name = props?.get("name")?.asString
                                    ?: props?.get("street")?.asString
                                    ?: props?.get("city")?.asString
                                    ?: q

                                val parts = listOfNotNull(
                                    props?.get("street")?.asString,
                                    props?.get("district")?.asString,
                                    props?.get("city")?.asString,
                                    props?.get("state")?.asString,
                                    props?.get("country")?.asString
                                )
                                val subText = if (parts.isNotEmpty()) parts.joinToString(", ") else "Localização Global"

                                val osmVal = props?.get("osm_value")?.asString
                                val category = when {
                                    osmVal == "city" -> PlaceCategory.CITY
                                    props?.has("street") == true -> PlaceCategory.ADDRESS
                                    else -> PlaceCategory.LANDMARK
                                }

                                results.add(
                                    SearchResultItem(
                                        id = "osm_${lat}_${lng}_$i",
                                        name = name,
                                        subText = subText,
                                        category = category,
                                        point = GeoPoint(lat, lng, 20.0)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        if (results.isNotEmpty()) return@withContext results

        // 2. Nominatim fallback
        try {
            val nomUrl = "https://nominatim.openstreetmap.org/search?format=json&q=${URLEncoder.encode(q, "UTF-8")}&limit=5"
            val request = Request.Builder()
                .url(nomUrl)
                .header("User-Agent", "EBikeRouterAndroid/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val arr = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                    for (i in 0 until arr.size()) {
                        val item = arr.get(i).asJsonObject
                        val lat = item.get("lat").asString.toDouble()
                        val lon = item.get("lon").asString.toDouble()
                        val displayName = item.get("display_name").asString
                        val title = displayName.split(",").firstOrNull() ?: q

                        results.add(
                            SearchResultItem(
                                id = "nom_${item.get("place_id")?.asString ?: i}",
                                name = title.trim(),
                                subText = displayName.trim(),
                                category = PlaceCategory.ADDRESS,
                                point = GeoPoint(lat, lon, 20.0)
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        return@withContext results
    }
}
