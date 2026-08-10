package com.trucknav.pro.voice

import com.trucknav.pro.model.LatLng
import com.trucknav.pro.model.RouteInstruction
import com.trucknav.pro.model.TruckRoute
import com.trucknav.pro.routing.RouteGeometry

/**
 * Turns raw GPS updates into turn-by-turn progress: which maneuver is next,
 * how far away it is, and when to fire a voice announcement for it.
 *
 * Built on top of the route's own polyline + instruction list rather than any
 * SDK "distance remaining" callback, so it works the same regardless of which
 * TomTom SDK surface ends up supplying the route.
 */
class RouteProgressTracker(
    private val route: TruckRoute,
    private val listener: Listener
) {
    interface Listener {
        fun onInstructionChanged(instruction: RouteInstruction)
        fun onAnnounce(text: String)
        fun onProgress(distanceRemainingMeters: Double, etaMillis: Long)
        fun onArrived()
    }

    private val cumulative = RouteGeometry.cumulativeDistances(route.path)
    private var currentInstructionIndex = 0
    private val announced = mutableSetOf<Pair<Int, Double>>()
    private var arrived = false

    init {
        route.instructions.firstOrNull()?.let { listener.onInstructionChanged(it) }
    }

    /** Feed each new GPS fix in here while navigation is active. */
    fun onLocationUpdate(current: LatLng) {
        if (route.instructions.isEmpty() || arrived) return

        val progressMeters = RouteGeometry.progressAlongRoute(route.path, cumulative, current)

        while (currentInstructionIndex < route.instructions.size - 1 &&
            progressMeters >= route.instructions[currentInstructionIndex].distanceFromRouteStartMeters
        ) {
            currentInstructionIndex++
            listener.onInstructionChanged(route.instructions[currentInstructionIndex])
        }

        val instruction = route.instructions[currentInstructionIndex]
        val remaining = (instruction.distanceFromRouteStartMeters - progressMeters).coerceAtLeast(0.0)

        for ((thresholdMeters, prefix) in ANNOUNCEMENT_POINTS) {
            val key = currentInstructionIndex to thresholdMeters
            if (remaining <= thresholdMeters && key !in announced) {
                announced += key
                listener.onAnnounce(prefix + instruction.text)
            }
        }

        val totalRemaining = (route.distanceMeters - progressMeters).coerceAtLeast(0.0)
        val fractionRemaining = if (route.distanceMeters > 0) (totalRemaining / route.distanceMeters) else 0.0
        val etaMillis = System.currentTimeMillis() + (route.travelTimeSeconds * 1000 * fractionRemaining).toLong()
        listener.onProgress(totalRemaining, etaMillis)

        if (currentInstructionIndex == route.instructions.size - 1 && totalRemaining < ARRIVAL_RADIUS_METERS) {
            arrived = true
            listener.onArrived()
        }
    }

    private companion object {
        const val ARRIVAL_RADIUS_METERS = 40.0

        // (trigger distance in meters, spoken prefix). Checked from largest to smallest.
        val ANNOUNCEMENT_POINTS = listOf(
            1609.0 to "In 1 mile, ",
            804.0 to "In a half mile, ",
            300.0 to "In 1,000 feet, ",
            60.0 to ""
        )
    }
}
