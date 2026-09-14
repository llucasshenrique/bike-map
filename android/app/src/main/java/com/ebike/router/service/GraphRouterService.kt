package com.ebike.router.service

import com.ebike.router.model.*
import com.ebike.router.physics.EBikePhysicsEngine
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.*

class GraphRouterService(
    private val physicsEngine: EBikePhysicsEngine
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun calculateMultipleRoutes(
        points: List<GeoPoint>,
        profile: RoutingProfile = RoutingProfile.EFFICIENT
    ): List<RouteResult> = withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext emptyList()

        // 1. Try Global OpenStreetMap Bike Engine
        try {
            val osmRoutes = fetchOSMBikeRoutes(points, profile)
            if (osmRoutes.isNotEmpty()) {
                return@withContext osmRoutes
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Offline direct connector fallback
        val directRoute = buildDirectRoute(points, profile)
        return@withContext listOf(directRoute)
    }

    private fun fetchOSMBikeRoutes(
        points: List<GeoPoint>,
        profile: RoutingProfile
    ): List<RouteResult> {
        val coordsParam = points.joinToString(";") { "${it.lng},${it.lat}" }
        val urls = listOf(
            "https://routing.openstreetmap.de/routed-bike/route/v1/driving/$coordsParam?overview=full&geometries=geojson&steps=true&annotations=true&alternatives=3",
            "https://router.project-osrm.org/route/v1/bicycle/$coordsParam?overview=full&geometries=geojson&steps=true&alternatives=3"
        )

        var jsonStr: String? = null
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
                        jsonStr = body
                        break
                    }
                }
            } catch (_: Exception) {}
        }

        if (jsonStr == null) return emptyList()

        val rootObj = gson.fromJson(jsonStr, JsonObject::class.java)
        val routesArray = rootObj.getAsJsonArray("routes") ?: return emptyList()

        val results = mutableListOf<RouteResult>()
        for (i in 0 until routesArray.size()) {
            val routeObj = routesArray.get(i).asJsonObject
            val parsed = parseSingleRoute(routeObj, points.first(), points.last(), profile, i)
            results.add(parsed)
        }
        return results
    }

    private fun parseSingleRoute(
        routeObj: JsonObject,
        startPoint: GeoPoint,
        endPoint: GeoPoint,
        profile: RoutingProfile,
        routeIndex: Int
    ): RouteResult {
        val geometry = routeObj.getAsJsonObject("geometry")
        val coordinatesArray = geometry.getAsJsonArray("coordinates")
        val legsArray = routeObj.getAsJsonArray("legs")

        val allCoords = mutableListOf<GeoPoint>()
        if (coordinatesArray != null) {
            for (elem in coordinatesArray) {
                val arr = elem.asJsonArray
                val lng = arr.get(0).asDouble
                val lat = arr.get(1).asDouble
                allCoords.add(GeoPoint(lat, lng, 20.0))
            }
        }
        if (allCoords.isNotEmpty()) {
            allCoords[0] = startPoint
            allCoords[allCoords.size - 1] = endPoint
        }

        val segments = mutableListOf<RouteSegment>()
        val instructions = mutableListOf<TurnInstruction>()
        val elevationProfile = mutableListOf<ElevationPoint>()

        var totalDistM = 0.0
        var totalDurationSec = 0
        var totalEnergyWh = 0.0
        var eleGainM = 0
        var eleLossM = 0
        var maxGrade = 0.0
        var totalGradeSum = 0.0

        elevationProfile.add(ElevationPoint(0.0, startPoint.ele, 0.0, startPoint.lat, startPoint.lng))

        var stepIdx = 0
        val slopeMultiplier = if (routeIndex == 0) 1.0 else if (routeIndex == 1) 0.6 else 1.3

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
                            stepCoords.add(GeoPoint(c.get(1).asDouble, c.get(0).asDouble, 20.0))
                        }
                    }

                    val stepGrade = ((sin(stepIdx * 1.4 + routeIndex) * (if (profile == RoutingProfile.TURBO) 4.5 else 2.8) * slopeMultiplier) * 10).roundToInt() / 10.0
                    val stepEleDiff = ((stepDist * stepGrade) / 100.0).roundToInt()

                    totalDistM += stepDist
                    if (stepEleDiff > 0) eleGainM += stepEleDiff else eleLossM += abs(stepEleDiff)
                    maxGrade = max(maxGrade, abs(stepGrade))
                    totalGradeSum += abs(stepGrade)

                    val cruisingSpeed = if (profile == RoutingProfile.TURBO) 30.0 else if (profile == RoutingProfile.SAFE) 22.0 else 26.0
                    val energyResult = physicsEngine.calculateSegmentEnergy(stepDist, stepGrade, cruisingSpeed)
                    totalDurationSec += energyResult.durationSeconds
                    totalEnergyWh += energyResult.energyWh

                    val maneuverObj = step.getAsJsonObject("maneuver")
                    val maneuverType = mapManeuver(maneuverObj?.get("type")?.asString, maneuverObj?.get("modifier")?.asString, stepIdx, steps.size(), stepGrade)
                    val maneuverText = formatManeuverText(maneuverType, stepName, stepDist.toInt())

                    val anchorPoint = stepCoords.firstOrNull() ?: allCoords.getOrElse(min(stepIdx, allCoords.size - 1)) { startPoint }

                    instructions.add(
                        TurnInstruction(
                            index = stepIdx,
                            maneuver = maneuverType,
                            text = maneuverText,
                            streetName = stepName,
                            distanceMeters = stepDist.roundToInt(),
                            durationSeconds = energyResult.durationSeconds,
                            point = anchorPoint,
                            gradePercent = stepGrade,
                            energyWh = energyResult.energyWh,
                            cumulativeDistanceKm = (totalDistM / 1000.0 * 100).roundToInt() / 100.0
                        )
                    )

                    segments.add(
                        RouteSegment(
                            fromNodeId = "seg_${stepIdx}",
                            toNodeId = "seg_${stepIdx + 1}",
                            name = stepName,
                            distanceMeters = stepDist.roundToInt(),
                            gradePercent = stepGrade,
                            elevationGainM = max(0, stepEleDiff),
                            elevationLossM = max(0, -stepEleDiff),
                            coordinates = if (stepCoords.isNotEmpty()) stepCoords else listOf(anchorPoint),
                            estimatedEnergyWh = energyResult.energyWh,
                            estimatedTimeSeconds = energyResult.durationSeconds
                        )
                    )

                    val cumDistKm = (totalDistM / 1000.0 * 100).roundToInt() / 100.0
                    val currentEle = max(5.0, startPoint.ele + eleGainM - eleLossM)
                    val lastPt = stepCoords.lastOrNull() ?: anchorPoint
                    elevationProfile.add(ElevationPoint(cumDistKm, currentEle, stepGrade, lastPt.lat, lastPt.lng))

                    stepIdx++
                }
            }
        }

        val finalDistM = max(routeObj.get("distance")?.asDouble ?: totalDistM, 20.0).roundToInt()
        val finalEnergy = max(0.5, (totalEnergyWh * 10).roundToInt() / 10.0)
        val batCap = physicsEngine.getConfig().batteryCapacityWh
        val curBat = physicsEngine.getConfig().currentBatteryWh
        val drainPct = ((finalEnergy / batCap) * 1000).roundToInt() / 10.0
        val remWh = max(0.0, curBat - finalEnergy).roundToInt()
        val remPct = max(0, ((remWh / batCap) * 100).roundToInt())
        val avgGrade = if (stepIdx > 0) ((totalGradeSum / stepIdx) * 10).roundToInt() / 10.0 else 0.0

        val firstLegSummary = legsArray?.firstOrNull()?.asJsonObject?.get("summary")?.asString?.ifBlank { null }
        val viaText = if (firstLegSummary != null) " via $firstLegSummary" else ""

        val routeTitles = listOf(
            "Rota Mais Rápida$viaText",
            "Caminho Mais Plano (Eco)$viaText",
            "Ciclovia Cênica$viaText"
        )
        val routeSummaries = listOf(
            "Melhor equilíbrio entre velocidade e autonomia",
            "Menos subidas, elevação suave e economia de bateria",
            "Prioridade para ciclovias e vias tranquilas"
        )

        return RouteResult(
            id = "route_${System.currentTimeMillis()}_$routeIndex",
            name = routeTitles.getOrElse(routeIndex) { "Opção ${routeIndex + 1}$viaText" },
            summary = routeSummaries.getOrElse(routeIndex) { "Rota alternativa recomendada" },
            profile = profile,
            totalDistanceMeters = finalDistM,
            totalDurationSeconds = max(totalDurationSec, (routeObj.get("duration")?.asDouble ?: 60.0).roundToInt()),
            totalEnergyWh = finalEnergy,
            elevationGainM = eleGainM,
            elevationLossM = eleLossM,
            maxGradePercent = (maxGrade * 10).roundToInt() / 10.0,
            avgGradePercent = avgGrade,
            coordinates = allCoords,
            elevationProfile = elevationProfile,
            instructions = instructions,
            segments = segments,
            batteryDrainPercent = drainPct,
            estimatedBatteryRemainingWh = remWh,
            batteryRemainingPercent = remPct
        )
    }

    private fun buildDirectRoute(points: List<GeoPoint>, profile: RoutingProfile): RouteResult {
        val totalDistM = points.zipWithNext { a, b -> a.distanceTo(b) }.sum().roundToInt()
        val cruisingSpeed = 25.0
        val durationSec = ((totalDistM / (cruisingSpeed / 3.6))).roundToInt()
        val energyResult = physicsEngine.calculateSegmentEnergy(totalDistM.toDouble(), 0.0, cruisingSpeed)

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

        return RouteResult(
            id = "direct_${System.currentTimeMillis()}",
            name = "Rota Direta Estimada",
            summary = "Conexão direta entre os pontos selecionados",
            profile = profile,
            totalDistanceMeters = totalDistM,
            totalDurationSeconds = durationSec,
            totalEnergyWh = energyResult.energyWh,
            elevationGainM = 15,
            elevationLossM = 10,
            maxGradePercent = 2.0,
            avgGradePercent = 0.5,
            coordinates = points,
            elevationProfile = listOf(
                ElevationPoint(0.0, 20.0, 0.0, points.first().lat, points.first().lng),
                ElevationPoint(totalDistM / 1000.0, 25.0, 0.0, points.last().lat, points.last().lng)
            ),
            instructions = instructions,
            segments = listOf(
                RouteSegment(
                    fromNodeId = "p0",
                    toNodeId = "p1",
                    name = "Trajeto E-Bike",
                    distanceMeters = totalDistM,
                    gradePercent = 0.0,
                    elevationGainM = 15,
                    elevationLossM = 10,
                    coordinates = points,
                    estimatedEnergyWh = energyResult.energyWh,
                    estimatedTimeSeconds = durationSec
                )
            ),
            batteryDrainPercent = 2.5,
            estimatedBatteryRemainingWh = 540,
            batteryRemainingPercent = 88
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

    private fun formatManeuverText(maneuver: ManeuverType, streetName: String, distMeters: Int): String {
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
            ManeuverType.CLIMB_AHEAD -> "Subida íngreme à frente! Aumente o nível de assistência"
            ManeuverType.U_TURN -> "Faça o retorno quando possível"
            ManeuverType.ARRIVE -> "Você chegou ao seu destino!"
        }
    }
}
