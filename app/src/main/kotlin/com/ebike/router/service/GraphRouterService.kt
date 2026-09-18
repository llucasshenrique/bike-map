package com.ebike.router.service

import android.content.Context
import com.ebike.router.data.dem.SrtmTileManager
import com.ebike.router.data.local.ElevationDatabase
import com.ebike.router.model.*
import com.ebike.router.physics.EBikePhysicsEngine
import com.ebike.router.service.elevation.CompositeElevationProvider
import com.ebike.router.service.elevation.ElevationProvider
import com.ebike.router.service.elevation.ElevationSampler
import com.ebike.router.service.elevation.ElevationSmoother
import com.ebike.router.service.elevation.RemoteElevationProvider
import com.ebike.router.service.elevation.SrtmElevationProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.*

class WayTagsIndex(
    val nodeTags: Map<Long, OsmWayTags> = emptyMap(),
    val nameTags: Map<String, OsmWayTags> = emptyMap()
) {
    fun findTags(name: String?, nodeId: Long? = null): OsmWayTags? {
        if (nodeId != null && nodeTags.containsKey(nodeId)) {
            return nodeTags[nodeId]
        }
        val cleanName = name?.trim()?.lowercase()
        if (!cleanName.isNullOrBlank() && nameTags.containsKey(cleanName)) {
            return nameTags[cleanName]
        }
        return null
    }
}

class GraphRouterService(
    private val physicsEngine: EBikePhysicsEngine,
    context: Context
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private val elevationProvider: ElevationProvider = CompositeElevationProvider(
        database = ElevationDatabase(context.applicationContext),
        srtmProvider = SrtmElevationProvider(SrtmTileManager(context.applicationContext)),
        remoteProvider = RemoteElevationProvider()
    )

    suspend fun calculateMultipleRoutes(
        points: List<GeoPoint>,
        profile: RoutingProfile = RoutingProfile.EFFICIENT,
        isEBikeMode: Boolean = false
    ): List<RouteResult> = withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext emptyList()

        // 1. Try Global OpenStreetMap Bike Engine with surface & safety intelligence
        try {
            val osmRoutes = fetchOSMBikeRoutes(points, profile, isEBikeMode)
            if (osmRoutes.isNotEmpty()) {
                return@withContext RouteClassifier.classify(osmRoutes)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Offline direct connector fallback
        val directRoute = buildDirectRoute(points, profile, isEBikeMode)
        return@withContext listOf(directRoute)
    }

    private suspend fun fetchOSMBikeRoutes(
        points: List<GeoPoint>,
        profile: RoutingProfile,
        isEBikeMode: Boolean
    ): List<RouteResult> {
        val rawRouteObjects = mutableListOf<Pair<JsonObject, String?>>()

        // Query standard OSRM routes
        val standardRoutes = fetchRawOSMRoutes(points)
        for (r in standardRoutes) {
            rawRouteObjects.add(r to null)
        }

        // Check if corridor has dedicated cycleways and discover a distinct bike-lane alternative
        val cyclewayWaypoint = findCyclewayWaypointInCorridor(points.first(), points.last())
        if (cyclewayWaypoint != null) {
            val viaPoints = listOf(points.first(), cyclewayWaypoint, points.last())
            val viaRoutes = fetchRawOSMRoutes(viaPoints)
            if (viaRoutes.isNotEmpty()) {
                val cyclewayRoute = viaRoutes.first()
                rawRouteObjects.add(cyclewayRoute to "Ciclovia Dedicada")
            }
        }

        if (rawRouteObjects.isEmpty()) return emptyList()

        // Check if OSM way tags are directly provided by OSRM response
        val directTagsAvailable = hasDirectTags(rawRouteObjects.map { it.first })
        val tagsIndex = if (!directTagsAvailable) {
            val nodeIds = extractOsmNodeIds(rawRouteObjects.map { it.first })
            queryOverpassForCorridorTags(points, nodeIds)
        } else {
            WayTagsIndex()
        }

        // Parse each route with real OSM way tags (surface, highway, cycleway)
        val parsedRoutes = mutableListOf<RouteResult>()
        for ((idx, pair) in rawRouteObjects.withIndex()) {
            val (routeObj, viaLabel) = pair
            val parsed = parseSingleRoute(routeObj, points.first(), points.last(), profile, idx, tagsIndex, viaLabel, isEBikeMode)
            parsedRoutes.add(parsed)
        }

        // Deduplicate routes with nearly identical geometry & distance
        val uniqueRoutes = mutableListOf<RouteResult>()
        for (r in parsedRoutes) {
            val duplicate = uniqueRoutes.any { existing ->
                abs(existing.totalDistanceMeters - r.totalDistanceMeters) < 25 &&
                        abs(existing.totalDurationSeconds - r.totalDurationSeconds) < 15 &&
                        abs(existing.coordinates.size - r.coordinates.size) < 3
            }
            if (!duplicate) {
                uniqueRoutes.add(r)
            }
        }

        if (uniqueRoutes.isEmpty()) return parsedRoutes

        // Rank routes according to the active RoutingProfile
        val rankedRoutes = when (profile) {
            RoutingProfile.SAFE -> {
                uniqueRoutes.sortedWith(
                    compareByDescending<RouteResult> { it.safetyScore }
                        .thenByDescending { it.bikeLanePercentage }
                        .thenBy { it.totalDurationSeconds }
                )
            }
            RoutingProfile.EFFICIENT -> {
                uniqueRoutes.sortedWith(
                    compareBy<RouteResult> { it.totalEnergyWh }
                        .thenBy { it.totalDistanceMeters }
                )
            }
            RoutingProfile.TURBO -> {
                uniqueRoutes.sortedWith(
                    compareBy<RouteResult> { it.totalDurationSeconds }
                        .thenBy { it.totalDistanceMeters }
                )
            }
            RoutingProfile.SCENIC -> {
                uniqueRoutes.sortedWith(
                    compareByDescending<RouteResult> { it.bikeLanePercentage }
                        .thenBy { it.elevationGainM }
                )
            }
        }

        // Generate profile-aware and descriptive route titles and summaries
        return rankedRoutes.mapIndexed { index, route ->
            val viaText = if (route.name.contains(" via ")) {
                " via " + route.name.substringAfter(" via ")
            } else ""

            val (title, summary) = when {
                index == 0 && profile == RoutingProfile.SAFE -> {
                    val bikePct = route.bikeLanePercentage.roundToInt()
                    val safetyRating = (route.safetyScore * 10).roundToInt() / 10.0
                    "Rota Segura (Ciclovias)$viaText" to
                            "$bikePct% em ciclovias/ciclofaixas • Índice de segurança: $safetyRating/10"
                }
                index == 0 && profile == RoutingProfile.EFFICIENT -> {
                    "Eco Eficiente$viaText" to
                            "Menor consumo de energia (${route.totalEnergyWh} Wh) • Superfície regular"
                }
                index == 0 && profile == RoutingProfile.TURBO -> {
                    "Rota Mais Rápida$viaText" to
                            "Trajeto mais veloz (${route.totalDurationSeconds / 60} min) em vias pavimentadas"
                }
                index == 0 && profile == RoutingProfile.SCENIC -> {
                    "Trajeto Cênico$viaText" to
                            "Parques, ciclovias e vias arborizadas de baixo fluxo"
                }
                route.bikeLanePercentage >= 35.0 -> {
                    "Via Segura (${route.bikeLanePercentage.roundToInt()}% Ciclovia)$viaText" to
                            "Prioriza ciclovias segregadas e ciclofaixas protegidas"
                }
                route.dominantSurface in listOf("gravel", "compacted", "unpaved", "dirt") -> {
                    "Alternativa Rústica (${route.dominantSurface})$viaText" to
                            "Superfície não pavimentada com maior resistência de rolamento"
                }
                index == 1 -> {
                    "Alternativa Rápida$viaText" to
                            "Conexão direta com equilíbrio entre velocidade e distância"
                }
                else -> {
                    "Opção ${index + 1}$viaText" to
                            "Trajeto alternativo (${route.pavedPercentage.roundToInt()}% pavimentado)"
                }
            }

            route.copy(
                name = title,
                summary = summary,
                profile = profile
            )
        }
    }

    private fun fetchRawOSMRoutes(points: List<GeoPoint>): List<JsonObject> {
        val coordsParam = points.joinToString(";") { "${it.lng},${it.lat}" }
        val urls = listOf(
            "https://routing.openstreetmap.de/routed-bike/route/v1/bicycle/$coordsParam?overview=full&geometries=geojson&steps=true&annotations=true&alternatives=3",
            "https://router.project-osrm.org/route/v1/bicycle/$coordsParam?overview=full&geometries=geojson&steps=true&annotations=true&alternatives=3",
            "https://routing.openstreetmap.de/routed-bike/route/v1/driving/$coordsParam?overview=full&geometries=geojson&steps=true&annotations=true&alternatives=3"
        )

        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "EBikeRouterAndroid/1.0")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null && body.contains("\"code\":\"Ok\"")) {
                        val rootObj = gson.fromJson(body, JsonObject::class.java)
                        val routesArray = rootObj.getAsJsonArray("routes") ?: continue
                        val list = mutableListOf<JsonObject>()
                        for (i in 0 until routesArray.size()) {
                            list.add(routesArray.get(i).asJsonObject)
                        }
                        if (list.isNotEmpty()) return list
                    }
                }
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    private fun findCyclewayWaypointInCorridor(startPoint: GeoPoint, endPoint: GeoPoint): GeoPoint? {
        try {
            val minLat = min(startPoint.lat, endPoint.lat) - 0.008
            val maxLat = max(startPoint.lat, endPoint.lat) + 0.008
            val minLng = min(startPoint.lng, endPoint.lng) - 0.008
            val maxLng = max(startPoint.lng, endPoint.lng) + 0.008

            val query = """
                [out:json][timeout:4];
                (
                  way["highway"="cycleway"]($minLat,$minLng,$maxLat,$maxLng);
                  way["cycleway"="track"]($minLat,$minLng,$maxLat,$maxLng);
                  way["cycleway"="lane"]($minLat,$minLng,$maxLat,$maxLng);
                );
                out tags center 15;
            """.trimIndent()

            val formBody = FormBody.Builder().add("data", query).build()
            val request = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .header("User-Agent", "EBikeRouterAndroid/1.0")
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val root = gson.fromJson(body, JsonObject::class.java)
            val elements = root.getAsJsonArray("elements") ?: return null

            val directDist = startPoint.distanceTo(endPoint)
            if (directDist < 250.0) return null

            var bestPoint: GeoPoint? = null
            var bestBalance = Double.MAX_VALUE

            for (elem in elements) {
                val wayObj = elem.asJsonObject
                val centerObj = wayObj.getAsJsonObject("center") ?: continue
                val cLat = centerObj.get("lat")?.asDouble ?: continue
                val cLng = centerObj.get("lon")?.asDouble ?: continue
                val candidate = GeoPoint(cLat, cLng, 20.0)

                val d1 = startPoint.distanceTo(candidate)
                val d2 = candidate.distanceTo(endPoint)
                val totalDetour = d1 + d2

                if (totalDetour < directDist * 1.35 && d1 > 120.0 && d2 > 120.0) {
                    val balance = abs(d1 - d2)
                    if (balance < bestBalance) {
                        bestBalance = balance
                        bestPoint = candidate
                    }
                }
            }
            return bestPoint
        } catch (_: Exception) {
            return null
        }
    }

    private fun hasDirectTags(routes: List<JsonObject>): Boolean {
        for (route in routes) {
            val legs = route.getAsJsonArray("legs") ?: continue
            for (l in 0 until legs.size()) {
                val steps = legs.get(l).asJsonObject.getAsJsonArray("steps") ?: continue
                for (s in 0 until steps.size()) {
                    val step = steps.get(s).asJsonObject
                    if (step.has("surface") || step.has("highway") || step.has("cycleway") ||
                        step.has("tags") || step.has("extra_tags")
                    ) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun extractOsmNodeIds(routes: List<JsonObject>): List<Long> {
        val nodeIds = mutableListOf<Long>()
        for (route in routes) {
            val legs = route.getAsJsonArray("legs") ?: continue
            for (l in 0 until legs.size()) {
                val leg = legs.get(l).asJsonObject
                val annotation = leg.getAsJsonObject("annotation") ?: continue
                val nodes = annotation.getAsJsonArray("nodes") ?: continue
                for (node in nodes) {
                    nodeIds.add(node.asLong)
                }
            }
        }
        return nodeIds
    }

    private fun queryOverpassForCorridorTags(
        points: List<GeoPoint>,
        nodeIds: List<Long>
    ): WayTagsIndex {
        val nodeTagsMap = HashMap<Long, OsmWayTags>()
        val nameTagsMap = HashMap<String, OsmWayTags>()

        try {
            if (nodeIds.isNotEmpty()) {
                val sampleStep = max(1, nodeIds.size / 60)
                val sampled = nodeIds.filterIndexed { idx, _ -> idx % sampleStep == 0 }
                val nodeIdsStr = sampled.joinToString(",")
                val query = "[out:json][timeout:5];node(id:$nodeIdsStr);way(bn);out tags body qt 80;"

                val formBody = FormBody.Builder().add("data", query).build()
                val request = Request.Builder()
                    .url("https://overpass-api.de/api/interpreter")
                    .header("User-Agent", "EBikeRouterAndroid/1.0")
                    .post(formBody)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val root = gson.fromJson(body, JsonObject::class.java)
                        val elements = root.getAsJsonArray("elements")
                        if (elements != null) {
                            for (elem in elements) {
                                val obj = elem.asJsonObject
                                if (obj.get("type")?.asString != "way") continue
                                val tagsObj = obj.getAsJsonObject("tags") ?: continue
                                val tags = parseWayTags(tagsObj)

                                tags.name?.let {
                                    val clean = it.trim().lowercase()
                                    if (clean.isNotEmpty()) {
                                        nameTagsMap[clean] = tags
                                    }
                                }
                                val wayNodes = obj.getAsJsonArray("nodes")
                                if (wayNodes != null) {
                                    for (n in wayNodes) {
                                        nodeTagsMap[n.asLong] = tags
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Also query corridor bounding box if street name mapping is sparse
            if (nameTagsMap.size < 5 && points.isNotEmpty()) {
                val minLat = points.minOf { it.lat } - 0.006
                val maxLat = points.maxOf { it.lat } + 0.006
                val minLng = points.minOf { it.lng } - 0.006
                val maxLng = points.maxOf { it.lng } + 0.006

                val bboxQuery = """
                    [out:json][timeout:5];
                    (
                      way["highway"]($minLat,$minLng,$maxLat,$maxLng);
                    );
                    out tags 80;
                """.trimIndent()

                val formBody = FormBody.Builder().add("data", bboxQuery).build()
                val request = Request.Builder()
                    .url("https://overpass-api.de/api/interpreter")
                    .header("User-Agent", "EBikeRouterAndroid/1.0")
                    .post(formBody)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val root = gson.fromJson(body, JsonObject::class.java)
                        val elements = root.getAsJsonArray("elements")
                        if (elements != null) {
                            for (elem in elements) {
                                val obj = elem.asJsonObject
                                val tagsObj = obj.getAsJsonObject("tags") ?: continue
                                val tags = parseWayTags(tagsObj)
                                tags.name?.let {
                                    val clean = it.trim().lowercase()
                                    if (clean.isNotEmpty()) {
                                        nameTagsMap.putIfAbsent(clean, tags)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Safe fallback to heuristics on network error or timeout
        }

        return WayTagsIndex(nodeTags = nodeTagsMap, nameTags = nameTagsMap)
    }

    private fun parseWayTags(tagsObj: JsonObject): OsmWayTags {
        val surface = tagsObj.get("surface")?.asString
        val highway = tagsObj.get("highway")?.asString
        val cycleway = tagsObj.get("cycleway")?.asString
            ?: tagsObj.get("cycleway:right")?.asString
            ?: tagsObj.get("cycleway:left")?.asString
            ?: tagsObj.get("cycleway:both")?.asString
        val bicycle = tagsObj.get("bicycle")?.asString
        val smoothness = tagsObj.get("smoothness")?.asString
        val name = tagsObj.get("name")?.asString

        return OsmWayTags(
            surface = surface,
            highway = highway,
            cycleway = cycleway,
            bicycle = bicycle,
            smoothness = smoothness,
            name = name
        )
    }

    private fun resolveStepTags(
        step: JsonObject,
        stepName: String,
        tagsIndex: WayTagsIndex
    ): OsmWayTags {
        // 1. Direct tags on OSRM step
        val tagsObj = step.getAsJsonObject("tags") ?: step.getAsJsonObject("extra_tags")
        val surface = step.get("surface")?.asString ?: tagsObj?.get("surface")?.asString
        val highway = step.get("highway")?.asString ?: tagsObj?.get("highway")?.asString
        val cycleway = step.get("cycleway")?.asString
            ?: tagsObj?.get("cycleway")?.asString
            ?: tagsObj?.get("cycleway:right")?.asString
            ?: tagsObj?.get("cycleway:left")?.asString
        val bicycle = step.get("bicycle")?.asString ?: tagsObj?.get("bicycle")?.asString
        val smoothness = step.get("smoothness")?.asString ?: tagsObj?.get("smoothness")?.asString

        if (surface != null || highway != null || cycleway != null) {
            return OsmWayTags(
                surface = surface,
                highway = highway,
                cycleway = cycleway,
                bicycle = bicycle,
                smoothness = smoothness,
                name = stepName
            )
        }

        // 2. Overpass matched tags
        val matched = tagsIndex.findTags(stepName)
        if (matched != null) {
            return matched
        }

        // 3. Fallback heuristics based on street name
        val lower = stepName.lowercase()
        return when {
            lower.contains("ciclovia") || lower.contains("ciclofaixa") || lower.contains("bike path") -> {
                OsmWayTags(surface = "asphalt", highway = "cycleway", cycleway = "track", bicycle = "designated", name = stepName)
            }
            lower.contains("trilha") || lower.contains("parque") || lower.contains("trail") -> {
                OsmWayTags(surface = "compacted", highway = "path", bicycle = "yes", name = stepName)
            }
            lower.contains("rodovia") || lower.contains("autovia") || lower.contains("expressa") -> {
                OsmWayTags(surface = "asphalt", highway = "primary", name = stepName)
            }
            lower.contains("avenida") || lower.contains("viaduto") -> {
                OsmWayTags(surface = "asphalt", highway = "secondary", name = stepName)
            }
            else -> {
                OsmWayTags(surface = "asphalt", highway = "residential", name = stepName)
            }
        }
    }

    /** Raw per-step data collected before real elevation/grade is resolved. */
    private data class StepData(
        val stepIdx: Int,
        val stepDist: Double,
        val stepName: String,
        val stepCoords: List<GeoPoint>,
        val maneuverObj: JsonObject?,
        val startGeomDistM: Double,
        val endGeomDistM: Double,
        val stepTags: OsmWayTags,
        val surfaceName: String,
        val isBikeLane: Boolean,
        val rollingMultiplier: Double,
        val safetyScore: Double
    )

    private suspend fun parseSingleRoute(
        routeObj: JsonObject,
        startPoint: GeoPoint,
        endPoint: GeoPoint,
        profile: RoutingProfile,
        routeIndex: Int,
        tagsIndex: WayTagsIndex,
        customViaLabel: String?,
        isEBikeMode: Boolean = true
    ): RouteResult {
        val geometry = routeObj.getAsJsonObject("geometry")
        val coordinatesArray = geometry?.getAsJsonArray("coordinates")
        val legsArray = routeObj.getAsJsonArray("legs")

        val allCoords = mutableListOf<GeoPoint>()
        if (coordinatesArray != null) {
            for (elem in coordinatesArray) {
                val arr = elem.asJsonArray
                val lng = arr.get(0).asDouble
                val lat = arr.get(1).asDouble
                allCoords.add(GeoPoint(lat, lng))
            }
        }
        if (allCoords.isNotEmpty()) {
            allCoords[0] = startPoint
            allCoords[allCoords.size - 1] = endPoint
        }

        // First pass: walk every leg/step, recording raw step metadata and
        // concatenating step geometries into one polyline used to sample
        // real ground elevation along the whole route.
        val fullRouteCoords = mutableListOf<GeoPoint>()
        val anchorIndices = mutableSetOf<Int>()
        val stepDataList = mutableListOf<StepData>()
        var geomCumDist = 0.0

        var totalBikeLaneDistM = 0.0
        var totalPavedDistM = 0.0
        var totalSafetyWeighted = 0.0
        val surfaceDistances = mutableMapOf<String, Double>()
        var stepIdx = 0

        if (legsArray != null) {
            for (l in 0 until legsArray.size()) {
                val leg = legsArray.get(l).asJsonObject
                val steps = leg.getAsJsonArray("steps") ?: continue

                for (s in 0 until steps.size()) {
                    val step = steps.get(s).asJsonObject
                    val stepDist = step.get("distance")?.asDouble ?: 15.0
                    val stepName = step.get("name")?.asString?.ifBlank { null }
                        ?: if (stepIdx == 0) "Início da Rota" else "Ciclovia / Rua"

                    val stepGeom = step.getAsJsonObject("geometry")
                    val stepCoordsArr = stepGeom?.getAsJsonArray("coordinates")
                    val stepCoords = mutableListOf<GeoPoint>()
                    if (stepCoordsArr != null) {
                        for (coord in stepCoordsArr) {
                            val c = coord.asJsonArray
                            stepCoords.add(GeoPoint(c.get(1).asDouble, c.get(0).asDouble))
                        }
                    }
                    if (stepCoords.isNotEmpty() && stepIdx == 0) {
                        stepCoords[0] = startPoint
                    }

                    anchorIndices.add(fullRouteCoords.size)
                    val startGeomDist = geomCumDist
                    for (i in stepCoords.indices) {
                        if (i == 0 && fullRouteCoords.isNotEmpty()) {
                            geomCumDist += fullRouteCoords.last().distanceTo(stepCoords[i])
                        } else if (i > 0) {
                            geomCumDist += stepCoords[i - 1].distanceTo(stepCoords[i])
                        }
                        fullRouteCoords.add(stepCoords[i])
                    }
                    val endGeomDist = geomCumDist

                    val stepTags = resolveStepTags(step, stepName, tagsIndex)
                    val isBikeLane = stepTags.isBikeLaneOrCycleway()
                    val rollingMultiplier = stepTags.getRollingResistanceMultiplier()
                    val safetyScore = stepTags.getSafetyScore()
                    val surfaceName = stepTags.surface?.lowercase()?.trim() ?: "asphalt"

                    if (isBikeLane) totalBikeLaneDistM += stepDist
                    if (stepTags.isPaved()) totalPavedDistM += stepDist
                    totalSafetyWeighted += (safetyScore * stepDist)
                    surfaceDistances[surfaceName] = (surfaceDistances[surfaceName] ?: 0.0) + stepDist

                    val maneuverObj = step.getAsJsonObject("maneuver")
                    stepDataList.add(
                        StepData(
                            stepIdx = stepIdx,
                            stepDist = stepDist,
                            stepName = stepName,
                            stepCoords = stepCoords,
                            maneuverObj = maneuverObj,
                            startGeomDistM = startGeomDist,
                            endGeomDistM = endGeomDist,
                            stepTags = stepTags,
                            surfaceName = surfaceName,
                            isBikeLane = isBikeLane,
                            rollingMultiplier = rollingMultiplier,
                            safetyScore = safetyScore
                        )
                    )
                    stepIdx++
                }
            }
        }
        if (fullRouteCoords.isNotEmpty()) {
            fullRouteCoords[fullRouteCoords.size - 1] = endPoint
        }

        val sampler = ElevationSampler.build(
            routeCoords = fullRouteCoords.ifEmpty { listOf(startPoint, endPoint) },
            anchorIndices = anchorIndices,
            provider = elevationProvider
        )

        val startEle = sampler.elevationAt(0.0)
        val resolvedStart = startPoint.copy(ele = startEle)
        if (allCoords.isNotEmpty()) allCoords[0] = resolvedStart

        // Second pass: derive real grade/energy/instructions/segments from
        // the sampled + smoothed elevation profile.
        val segments = mutableListOf<RouteSegment>()
        val instructions = mutableListOf<TurnInstruction>()
        val elevationProfile = mutableListOf<ElevationPoint>()

        var totalDistM = 0.0
        var totalDurationSec = 0
        var totalEnergyWh = 0.0
        var maxGrade = 0.0
        var totalGradeSum = 0.0

        elevationProfile.add(ElevationPoint(0.0, startEle, 0.0, resolvedStart.lat, resolvedStart.lng))

        for (data in stepDataList) {
            val eleStart = sampler.elevationAt(data.startGeomDistM)
            val eleEnd = sampler.elevationAt(data.endGeomDistM)
            val stepEleDiff = eleEnd - eleStart
            val stepGrade = ElevationSmoother.gradePercent(stepEleDiff, max(data.stepDist, 1.0))

            totalDistM += data.stepDist
            maxGrade = max(maxGrade, abs(stepGrade))
            totalGradeSum += abs(stepGrade)

            val cruisingSpeed = if (!isEBikeMode) {
                when (profile) {
                    RoutingProfile.TURBO -> 24.0
                    RoutingProfile.SAFE -> 18.0
                    RoutingProfile.SCENIC -> 19.0
                    RoutingProfile.EFFICIENT -> 22.0
                }
            } else {
                when (profile) {
                    RoutingProfile.TURBO -> min(30.0, physicsEngine.getConfig().maxAssistSpeedKmh)
                    RoutingProfile.SAFE -> 22.0
                    RoutingProfile.SCENIC -> 23.0
                    RoutingProfile.EFFICIENT -> min(26.0, physicsEngine.getConfig().maxAssistSpeedKmh)
                }
            }
            val assistLevel = if (isEBikeMode) physicsEngine.getConfig().activeAssist else AssistLevel.OFF
            val energyResult = physicsEngine.calculateSegmentEnergy(
                distanceMeters = data.stepDist,
                gradePercent = stepGrade,
                targetSpeedKmh = cruisingSpeed,
                assistLevel = assistLevel,
                rollingMultiplier = data.rollingMultiplier
            )
            totalDurationSec += energyResult.durationSeconds
            totalEnergyWh += energyResult.energyWh

            val maneuverType = mapManeuver(
                data.maneuverObj?.get("type")?.asString,
                data.maneuverObj?.get("modifier")?.asString,
                data.stepIdx,
                stepDataList.size,
                stepGrade
            )
            val maneuverText = formatManeuverText(maneuverType, data.stepName, data.stepDist.toInt(), isEBikeMode)

            val anchorPoint = data.stepCoords.firstOrNull()
                ?: allCoords.getOrElse(min(data.stepIdx, allCoords.size - 1)) { resolvedStart }

            instructions.add(
                TurnInstruction(
                    index = data.stepIdx,
                    maneuver = maneuverType,
                    text = maneuverText,
                    streetName = data.stepName,
                    distanceMeters = data.stepDist.roundToInt(),
                    durationSeconds = energyResult.durationSeconds,
                    point = anchorPoint,
                    gradePercent = stepGrade,
                    energyWh = energyResult.energyWh,
                    cumulativeDistanceKm = (totalDistM / 1000.0 * 100).roundToInt() / 100.0
                )
            )

            val stepEleGain = max(0.0, stepEleDiff).roundToInt()
            val stepEleLoss = max(0.0, -stepEleDiff).roundToInt()

            segments.add(
                RouteSegment(
                    fromNodeId = "seg_${data.stepIdx}",
                    toNodeId = "seg_${data.stepIdx + 1}",
                    name = data.stepName,
                    distanceMeters = data.stepDist.roundToInt(),
                    gradePercent = stepGrade,
                    elevationGainM = stepEleGain,
                    elevationLossM = stepEleLoss,
                    coordinates = if (data.stepCoords.isNotEmpty()) data.stepCoords else listOf(anchorPoint),
                    estimatedEnergyWh = energyResult.energyWh,
                    estimatedTimeSeconds = energyResult.durationSeconds,
                    surface = data.surfaceName,
                    highway = data.stepTags.highway,
                    isBikeLane = data.isBikeLane,
                    safetyScore = data.safetyScore,
                    rollingResistanceMultiplier = data.rollingMultiplier
                )
            )

            val cumDistKm = (totalDistM / 1000.0 * 100).roundToInt() / 100.0
            val lastPt = data.stepCoords.lastOrNull() ?: anchorPoint
            elevationProfile.add(ElevationPoint(cumDistKm, eleEnd, stepGrade, lastPt.lat, lastPt.lng))
        }

        val finalDistM = max(routeObj.get("distance")?.asDouble ?: totalDistM, 20.0).roundToInt()
        val finalEnergy = if (isEBikeMode) max(0.5, (totalEnergyWh * 10).roundToInt() / 10.0) else 0.0
        val batCap = physicsEngine.getConfig().batteryCapacityWh
        val curBat = physicsEngine.getConfig().currentBatteryWh
        val drainPct = if (isEBikeMode && batCap > 0) ((finalEnergy / batCap) * 1000).roundToInt() / 10.0 else 0.0
        val remWh = if (isEBikeMode) max(0.0, curBat - finalEnergy).roundToInt() else 0
        val remPct = if (isEBikeMode && batCap > 0) max(0, ((remWh / batCap) * 100).roundToInt()) else 0
        val avgGrade = if (stepDataList.isNotEmpty()) ((totalGradeSum / stepDataList.size) * 10).roundToInt() / 10.0 else 0.0

        val bikeLanePct = if (totalDistM > 0) ((totalBikeLaneDistM / totalDistM) * 1000).roundToInt() / 10.0 else 0.0
        val pavedPct = if (totalDistM > 0) ((totalPavedDistM / totalDistM) * 1000).roundToInt() / 10.0 else 100.0
        val avgSafety = if (totalDistM > 0) ((totalSafetyWeighted / totalDistM) * 100).roundToInt() / 10.0 else 0.8
        val dominantSurf = surfaceDistances.maxByOrNull { it.value }?.key ?: "asphalt"

        val firstLegSummary = legsArray?.firstOrNull()?.asJsonObject?.get("summary")?.asString?.ifBlank { null }
        val viaSummary = customViaLabel ?: firstLegSummary
        val viaText = if (viaSummary != null) " via $viaSummary" else ""

        return RouteResult(
            id = "route_${System.currentTimeMillis()}_$routeIndex",
            name = "Opção ${routeIndex + 1}$viaText",
            summary = "Trajeto alternativo",
            profile = profile,
            totalDistanceMeters = finalDistM,
            totalDurationSeconds = max(totalDurationSec, (routeObj.get("duration")?.asDouble ?: 60.0).roundToInt()),
            totalEnergyWh = finalEnergy,
            elevationGainM = sampler.elevationGainM,
            elevationLossM = sampler.elevationLossM,
            maxGradePercent = (maxGrade * 10).roundToInt() / 10.0,
            avgGradePercent = avgGrade,
            coordinates = allCoords,
            elevationProfile = elevationProfile,
            instructions = instructions,
            segments = segments,
            batteryDrainPercent = drainPct,
            estimatedBatteryRemainingWh = remWh,
            batteryRemainingPercent = remPct,
            safetyScore = avgSafety,
            bikeLanePercentage = bikeLanePct,
            pavedPercentage = pavedPct,
            dominantSurface = dominantSurf
        )
    }

    private fun buildDirectRoute(points: List<GeoPoint>, profile: RoutingProfile, isEBikeMode: Boolean = false): RouteResult {
        val totalDistM = points.zipWithNext { a, b -> a.distanceTo(b) }.sum().roundToInt()
        val cruisingSpeed = if (!isEBikeMode) {
            when (profile) {
                RoutingProfile.TURBO -> 24.0
                RoutingProfile.SAFE -> 18.0
                RoutingProfile.SCENIC -> 19.0
                RoutingProfile.EFFICIENT -> 22.0
            }
        } else {
            when (profile) {
                RoutingProfile.TURBO -> min(30.0, physicsEngine.getConfig().maxAssistSpeedKmh)
                RoutingProfile.SAFE -> 22.0
                RoutingProfile.SCENIC -> 24.0
                RoutingProfile.EFFICIENT -> min(26.0, physicsEngine.getConfig().maxAssistSpeedKmh)
            }
        }
        val assist = if (isEBikeMode) physicsEngine.getConfig().activeAssist else AssistLevel.OFF
        val energyResult = physicsEngine.calculateSegmentEnergy(totalDistM.toDouble(), 0.0, cruisingSpeed, assist)
        val durationSec = energyResult.durationSeconds

        val instructions = mutableListOf<TurnInstruction>()
        instructions.add(
            TurnInstruction(
                index = 0,
                maneuver = ManeuverType.DEPART,
                text = "Inicie a rota em direção ao destino",
                streetName = "Trajetória Estimada",
                distanceMeters = totalDistM,
                durationSeconds = durationSec,
                point = points.first(),
                gradePercent = 0.0,
                energyWh = energyResult.energyWh,
                cumulativeDistanceKm = (totalDistM / 1000.0 * 10).roundToInt() / 10.0
            )
        )
        instructions.add(
            TurnInstruction(
                index = 1,
                maneuver = ManeuverType.ARRIVE,
                text = "Você chegou ao seu destino!",
                streetName = "Destino",
                distanceMeters = 0,
                durationSeconds = 0,
                point = points.last(),
                gradePercent = 0.0,
                energyWh = 0.0,
                cumulativeDistanceKm = (totalDistM / 1000.0 * 10).roundToInt() / 10.0
            )
        )

        val segName = if (isEBikeMode) "Trajeto E-Bike" else "Trajeto de Bicicleta"
        return RouteResult(
            id = "direct_${System.currentTimeMillis()}",
            name = if (profile == RoutingProfile.SAFE) "Rota Segura Direta" else "Rota Direta Estimada",
            summary = if (profile == RoutingProfile.SAFE) "Trajeto em linha reta com estimativa de segurança" else "Conexão direta entre os pontos selecionados",
            profile = profile,
            totalDistanceMeters = totalDistM,
            totalDurationSeconds = durationSec,
            totalEnergyWh = if (isEBikeMode) energyResult.energyWh else 0.0,
            elevationGainM = 0,
            elevationLossM = 0,
            maxGradePercent = 0.0,
            avgGradePercent = 0.0,
            coordinates = points,
            elevationProfile = listOf(
                ElevationPoint(0.0, points.first().ele, 0.0, points.first().lat, points.first().lng),
                ElevationPoint(totalDistM / 1000.0, points.first().ele, 0.0, points.last().lat, points.last().lng)
            ),
            instructions = instructions,
            segments = listOf(
                RouteSegment(
                    fromNodeId = "p0",
                    toNodeId = "p1",
                    name = segName,
                    distanceMeters = totalDistM,
                    gradePercent = 0.0,
                    elevationGainM = 0,
                    elevationLossM = 0,
                    coordinates = points,
                    estimatedEnergyWh = if (isEBikeMode) energyResult.energyWh else 0.0,
                    estimatedTimeSeconds = durationSec,
                    surface = "asphalt",
                    highway = if (profile == RoutingProfile.SAFE) "cycleway" else "residential",
                    isBikeLane = (profile == RoutingProfile.SAFE),
                    safetyScore = if (profile == RoutingProfile.SAFE) 0.9 else 0.75,
                    rollingResistanceMultiplier = 1.0
                )
            ),
            batteryDrainPercent = if (isEBikeMode) 2.5 else 0.0,
            estimatedBatteryRemainingWh = if (isEBikeMode) 540 else 0,
            batteryRemainingPercent = if (isEBikeMode) 88 else 0,
            safetyScore = if (profile == RoutingProfile.SAFE) 0.9 else 0.75,
            bikeLanePercentage = if (profile == RoutingProfile.SAFE) 100.0 else 0.0,
            pavedPercentage = 100.0,
            dominantSurface = "asphalt"
        )
    }

    private fun mapManeuver(type: String?, modifier: String?, index: Int, total: Int, grade: Double): ManeuverType {
        if (index == 0) return ManeuverType.DEPART
        if (index >= total - 1) return ManeuverType.ARRIVE
        if (grade >= 6.0) return ManeuverType.CLIMB_AHEAD

        return when (modifier) {
            "sharp right" -> ManeuverType.SHARP_RIGHT
            "right" -> ManeuverType.TURN_RIGHT
            "slight right" -> ManeuverType.SLIGHT_RIGHT
            "sharp left" -> ManeuverType.SHARP_LEFT
            "left" -> ManeuverType.TURN_LEFT
            "slight left" -> ManeuverType.SLIGHT_LEFT
            "uturn" -> ManeuverType.U_TURN
            else -> if (type == "roundabout") ManeuverType.ROUNDABOUT else ManeuverType.STRAIGHT
        }
    }

    private fun formatManeuverText(maneuver: ManeuverType, streetName: String, distMeters: Int, isEBikeMode: Boolean = false): String {
        val distStr = if (distMeters >= 1000) "${(distMeters / 1000.0 * 10).roundToInt() / 10.0} km" else "$distMeters metros"
        return when (maneuver) {
            ManeuverType.DEPART -> "Siga em frente por $distStr na $streetName"
            ManeuverType.STRAIGHT -> "Continue em frente por $distStr na $streetName"
            ManeuverType.TURN_RIGHT -> "Vire à direita na $streetName ($distStr)"
            ManeuverType.SLIGHT_RIGHT -> "Mantenha-se à direita na $streetName ($distStr)"
            ManeuverType.SHARP_RIGHT -> "Curva fechada à direita na $streetName ($distStr)"
            ManeuverType.TURN_LEFT -> "Vire à esquerda na $streetName ($distStr)"
            ManeuverType.SLIGHT_LEFT -> "Mantenha-se à esquerda na $streetName ($distStr)"
            ManeuverType.SHARP_LEFT -> "Curva fechada à esquerda na $streetName ($distStr)"
            ManeuverType.ROUNDABOUT -> "Entre na rotatória em direção à $streetName"
            ManeuverType.CLIMB_AHEAD -> if (isEBikeMode) "Subida íngreme à frente! Ajuste o nível de assistência se necessário" else "Subida íngreme à frente! Reduza as marchas"
            ManeuverType.U_TURN -> "Faça o retorno quando possível"
            ManeuverType.ARRIVE -> "Você chegou ao seu destino!"
        }
    }
}
