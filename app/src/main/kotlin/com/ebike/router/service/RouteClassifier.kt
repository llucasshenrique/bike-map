package com.ebike.router.service

import com.ebike.router.model.RouteResult
import kotlin.math.roundToInt

/**
 * Assigns truthful route titles/summaries by comparing real computed
 * metrics (duration, elevation gain, energy, distance) across alternatives.
 * OSRM does not sort alternatives by slope, so the previous fixed
 * index-based labels ("Caminho Mais Plano" always on index 1) could easily
 * mislabel a hillier route as the flat one; this compares actual numbers.
 */
object RouteClassifier {
    private const val NOTABLE_FLAT_DELTA_M = 20
    private const val NOTABLE_FLAT_RATIO = 0.15

    fun classify(routes: List<RouteResult>): List<RouteResult> {
        if (routes.isEmpty()) return routes
        if (routes.size == 1) {
            val r = routes[0]
            return listOf(r.copy(name = withVia("Rota Recomendada", r), summary = "Melhor rota disponível para o trajeto"))
        }

        val fastest = routes.minBy { it.totalDurationSeconds }
        val flattest = routes.minBy { it.elevationGainM }
        val mostEfficient = routes.minBy { it.totalEnergyWh }
        val shortest = routes.minBy { it.totalDistanceMeters }

        return routes.map { route ->
            val isFastest = route === fastest
            val flatDeltaM = fastest.elevationGainM - route.elevationGainM
            val flatRatio = if (fastest.elevationGainM > 0) flatDeltaM.toDouble() / fastest.elevationGainM else 0.0
            val isNotablyFlatter = route === flattest && !isFastest &&
                flatDeltaM >= NOTABLE_FLAT_DELTA_M && flatRatio >= NOTABLE_FLAT_RATIO
            val isMostEfficient = route === mostEfficient && !isFastest && !isNotablyFlatter
            val isShortest = route === shortest && !isFastest && !isNotablyFlatter && !isMostEfficient

            val (name, summary) = when {
                isFastest -> withVia("Mais Rápida", route) to
                    "Menor tempo de trajeto (${route.totalDurationSeconds / 60} min)"
                isNotablyFlatter -> withVia("Mais Plana (Eco)", route) to
                    "▲ ${route.elevationGainM}m de subida (${(flatRatio * 100).roundToInt()}% menos subidas)"
                isMostEfficient -> withVia("Mais Econômica", route) to
                    "Consome apenas ${(route.totalEnergyWh * 10).roundToInt() / 10.0} Wh de bateria"
                isShortest -> withVia("Mais Curta", route) to
                    "Trajeto mais direto (${(route.totalDistanceMeters / 1000.0 * 10).roundToInt() / 10.0} km)"
                else -> withVia("Alternativa", route) to
                    "Boa alternativa com ${route.elevationGainM}m de subida"
            }
            route.copy(name = name, summary = summary)
        }
    }

    private fun withVia(base: String, route: RouteResult): String {
        val street = route.segments.firstOrNull { it.name.isNotBlank() && it.name != "Ciclovia / Rua" }?.name
        return if (street != null) "$base via $street" else base
    }
}
