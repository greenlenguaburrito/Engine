package com.trucknav.pro.location

import android.content.Context
import com.tomtom.sdk.location.GeoLocation
import com.tomtom.sdk.location.GeoPoint
import com.tomtom.sdk.location.LocationProvider
import com.tomtom.sdk.location.OnLocationUpdateListener

/**
 * Bridges our own FusedLocationProviderClient-based [LocationTracker] into TomTom's
 * [LocationProvider] interface, which is what TomTomMap.setLocationProvider()/
 * enableLocationMarker() need to draw the "you are here" dot on the map. Owns its
 * own LocationTracker instance so it doesn't fight over callbacks with whichever
 * tracker MainActivity uses for camera-follow during active navigation.
 */
class TomTomLocationProviderAdapter(context: Context) : LocationProvider {

    private val tracker = LocationTracker(context)
    private val listeners = mutableSetOf<OnLocationUpdateListener>()

    override var lastKnownLocation: GeoLocation? = null
        private set

    override fun enable() {
        tracker.start(intervalMillis = 2000L) { location, _ ->
            val geoLocation = GeoLocation(position = GeoPoint(location.latitude, location.longitude))
            lastKnownLocation = geoLocation
            listeners.toList().forEach { it.onLocationUpdate(geoLocation) }
        }
    }

    override fun disable() {
        tracker.stop()
    }

    override fun addOnLocationUpdateListener(listener: OnLocationUpdateListener) {
        listeners += listener
    }

    override fun removeOnLocationUpdateListener(listener: OnLocationUpdateListener) {
        listeners -= listener
    }

    override fun close() {
        disable()
        listeners.clear()
    }
}
