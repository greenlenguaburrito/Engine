package com.trucknav.pro.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Lightweight coordinate so the rest of the app doesn't depend directly on SDK types. */
data class LatLng(val latitude: Double, val longitude: Double) {

    /** Haversine distance to [other] in meters. */
    fun distanceTo(other: LatLng): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)

        val a = sin(dLat / 2) * sin(dLat / 2) +
            sin(dLon / 2) * sin(dLon / 2) * cos(lat1) * cos(lat2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
