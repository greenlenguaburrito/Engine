package com.trucknav.pro.model

/** A single turn-by-turn maneuver, offset by cumulative distance from the route start. */
data class RouteInstruction(
    val text: String,
    val distanceFromRouteStartMeters: Double,
    val point: LatLng,
    // Raw maneuver code from the routing API (e.g. "SHARP_LEFT", "MAKE_UTURN"), if provided.
    val maneuver: String? = null,
    // True for sharp turns and U-turns -- radiuses tight enough to warrant extra
    // warning for a full-size semi. There is no grade/elevation data available from
    // the routing API, so this can only cover turn geometry, not steep grades or
    // runaway-truck ramps.
    val isSharpTurn: Boolean = false
)

enum class TrafficSeverity { MINOR, MODERATE, MAJOR, CLOSURE }

/** A congested stretch of the route, as a range of indices into [TruckRoute.path]. */
data class TrafficSegment(
    val startIndex: Int,
    val endIndex: Int,
    val severity: TrafficSeverity
)

/** A truck-legal route as returned by the routing engine, trimmed to what the UI/voice guidance need. */
data class TruckRoute(
    val path: List<LatLng>,
    val instructions: List<RouteInstruction>,
    val distanceMeters: Double,
    val travelTimeSeconds: Long,
    val trafficDelaySeconds: Long,
    val arrivalTimeMillis: Long,
    val trafficSegments: List<TrafficSegment> = emptyList()
)
