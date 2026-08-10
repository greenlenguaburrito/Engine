package com.trucknav.pro.model

/** A single turn-by-turn maneuver, offset by cumulative distance from the route start. */
data class RouteInstruction(
    val text: String,
    val distanceFromRouteStartMeters: Double,
    val point: LatLng
)

/** A truck-legal route as returned by the routing engine, trimmed to what the UI/voice guidance need. */
data class TruckRoute(
    val path: List<LatLng>,
    val instructions: List<RouteInstruction>,
    val distanceMeters: Double,
    val travelTimeSeconds: Long,
    val trafficDelaySeconds: Long,
    val arrivalTimeMillis: Long
)
