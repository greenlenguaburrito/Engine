package com.trucknav.pro.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.trucknav.pro.model.LatLng

/**
 * Thin wrapper around FusedLocationProviderClient. Callers must have already
 * checked ACCESS_FINE_LOCATION before calling [start] or [requestOnce].
 */
class LocationTracker(context: Context) {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    fun requestOnce(onLocation: (LatLng) -> Unit, onFailure: () -> Unit) {
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .build()

        client.getCurrentLocation(request, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    onLocation(LatLng(location.latitude, location.longitude))
                } else {
                    onFailure()
                }
            }
            .addOnFailureListener { onFailure() }
    }

    @SuppressLint("MissingPermission")
    fun start(intervalMillis: Long = 1000L, onLocation: (LatLng, Float) -> Unit) {
        stop()

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis / 2)
            .build()

        val newCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                onLocation(LatLng(location.latitude, location.longitude), location.bearing)
            }
        }
        callback = newCallback
        client.requestLocationUpdates(request, newCallback, null)
    }

    fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }
}
