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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.*

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
        profile: RoutingProfile = RoutingProfile.EFFICIENT
    ): List<RouteResult> = withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext emptyList()

        // 1. Try Global OpenStreetMap Bike Engine
        try {
            val osmRoutes = fetchOSMBikeRoutes(points, profile)
            if (osmRoutes.isNotEmpty()) {
                return@withContext RouteClassifier.classify(osmRoutes)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Offline direct connector fallback
        val directRoute = buildDirectRoute(points, profile)
        return@withContext listOf(directRoute)
    }

    private suspend fun fetchOSMBikeRoutes(
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

    /** Raw per-step data collected before real elevation/grade is resolved. */
    private data class StepData(
        val stepIdx: Int,
        val stepDist: Double,
        val stepName: String,
        val stepCoords: List<GeoPoint>,
        val maneuverObj: JsonObject?,
        val startGeomDistM: Double,
        val endGeomDistM: Double
    )

    private suspend fun parseSingleRoute(
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

                    val maneuverObj = step.getAsJsonObject("maneuver")
                    stepDataList.add(
                        StepData(stepIdx, stepDist, stepName, stepCoords, maneuverObj, startGeomDist, endGeomDist)
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

            val cruisingSpeed = if (profile == RoutingProfile.TURBO) 30.0 else if (profile == RoutingProfile.SAFE) 22.0 else 26.0
            val energyResult = physicsEngine.calculateSegmentEnergy(data.stepDist, stepGrade, cruisingSpeed)
            totalDurationSec += energyResult.durationSeconds
            totalEnergyWh += energyResult.energyWh

            val maneuverType = mapManeuver(
                data.maneuverObj?.get("type")?.asString,
                data.maneuverObj?.get("modifier")?.asString,
                data.stepIdx,
                stepDataList.size,
                stepGrade
            )
            val maneuverText = formatManeuverText(maneuverType, data.stepName, data.stepDist.toInt())

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
                    estimatedTimeSeconds = energyResult.durationSeconds
                )
            )

            val cumDistKm = (totalDistM / 1000.0 * 100).roundToInt() / 100.0
            val lastPt = data.stepCoords.lastOrNull() ?: anchorPoint
            elevationProfile.add(ElevationPoint(cumDistKm, eleEnd, stepGrade, lastPt.lat, lastPt.lng))
        }

        val finalDistM = max(routeObj.get("distance")?.asDouble ?: totalDistM, 20.0).roundToInt()
        val finalEnergy = max(0.5, (totalEnergyWh * 10).roundToInt() / 10.0)
        val batCap = physicsEngine.getConfig().batteryCapacityWh
        val curBat = physicsEngine.getConfig().currentBatteryWh
        val drainPct = ((finalEnergy / batCap) * 1000).roundToInt() / 10.0
        val remWh = max(0.0, curBat - finalEnergy).roundToInt()
        val remPct = max(0, ((remWh / batCap) * 100).roundToInt())
        val avgGrade = if (stepDataList.isNotEmpty()) ((totalGradeSum / stepDataList.size) * 10).roundToInt() / 10.0 else 0.0

        return RouteResult(
            id = "route_${System.currentTimeMillis()}_$routeIndex",
            name = "Opção ${routeIndex + 1}",
            summary = "Rota alternativa recomendada",
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
            summary = "Conexão direta entre os pontos selecionados (sem dados de elevação)",
            profile = profile,
            totalDistanceMeters = totalDistM,
            totalDurationSeconds = durationSec,
            totalEnergyWh = energyResult.energyWh,
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
                    name = "Trajeto E-Bike",
                    distanceMeters = totalDistM,
                    gradePercent = 0.0,
                    elevationGainM = 0,
                    elevationLossM = 0,
                    coordinates = points,
                    estimatedEnergyWh = energyResult.energyWh,
                    estimatedTimeSeconds = durationSec
                )
            ),
            batteryDrainPercent = 0.0,
            estimatedBatteryRemainingWh = physicsEngine.getConfig().currentBatteryWh.roundToInt(),
            batteryRemainingPercent = 100
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
