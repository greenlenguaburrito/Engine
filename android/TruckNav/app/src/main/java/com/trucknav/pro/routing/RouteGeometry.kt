package com.trucknav.pro.routing

import com.trucknav.pro.model.LatLng

/**
 * Cumulative-distance helpers for a route polyline.
 *
 * We compute maneuver offsets ourselves (rather than trusting an SDK-provided
 * "distance from start" field) so voice guidance keeps working even if the
 * exact field name on the routing response changes between SDK versions.
 */
object RouteGeometry {

    /** Cumulative distance (meters) from the start of [path] to each vertex. */
    fun cumulativeDistances(path: List<LatLng>): DoubleArray {
        val out = DoubleArray(path.size)
        for (i in 1 until path.size) {
            out[i] = out[i - 1] + path[i - 1].distanceTo(path[i])
        }
        return out
    }

    /** Distance from the start of [path] to the vertex nearest [target]. */
    fun distanceFromStartTo(path: List<LatLng>, cumulative: DoubleArray, target: LatLng): Double {
        if (path.isEmpty()) return 0.0
        var bestIndex = 0
        var bestDist = Double.MAX_VALUE
        for (i in path.indices) {
            val d = path[i].distanceTo(target)
            if (d < bestDist) {
                bestDist = d
                bestIndex = i
            }
        }
        return cumulative[bestIndex]
    }

    /**
     * Distance traveled along [path] up to the point closest to [current],
     * used to know how far the driver has progressed during active navigation.
     */
    fun progressAlongRoute(path: List<LatLng>, cumulative: DoubleArray, current: LatLng): Double =
        distanceFromStartTo(path, cumulative, current)
}
