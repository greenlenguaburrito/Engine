package com.trucknav.pro.routing

import com.trucknav.pro.BuildConfig
import com.trucknav.pro.model.LatLng
import com.trucknav.pro.model.RouteInstruction
import com.trucknav.pro.model.TruckProfile
import com.trucknav.pro.model.TruckRoute
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

sealed class RouteResult {
    data class Success(val route: TruckRoute) : RouteResult()
    data class Error(val message: String) : RouteResult()
}

/**
 * Plans truck-legal routes against TomTom's public Routing REST API
 * (https://api.tomtom.com/routing/1/calculateRoute) rather than the native
 * Routing SDK's Vehicle/RoutePlanner object model. See the note in
 * app/build.gradle.kts for why -- this is the same endpoint/schema the
 * original web prototype used, and it honors weight/height/length/hazmat
 * restrictions with a standard TomTom API key.
 */
class RouteRepository {

    private val client = OkHttpClient()

    fun planTruckRoute(
        origin: LatLng,
        destination: LatLng,
        profile: TruckProfile,
        onResult: (RouteResult) -> Unit
    ) {
        val path = "https://api.tomtom.com/routing/1/calculateRoute/" +
            "${origin.latitude},${origin.longitude}:${destination.latitude},${destination.longitude}/json"

        val urlBuilder = path.toHttpUrl().newBuilder()
            .addQueryParameter("key", BuildConfig.TOMTOM_API_KEY)
            .addQueryParameter("travelMode", "truck")
            .addQueryParameter("vehicleCommercial", "true")
            .addQueryParameter("vehicleWeight", profile.weightKg.toString())
            .addQueryParameter("vehicleHeight", (profile.heightCm / 100.0).toString())
            .addQueryParameter("vehicleLength", (profile.lengthCm / 100.0).toString())
            .addQueryParameter("traffic", "true")
            .addQueryParameter("computeTravelTimeFor", "all")
            .addQueryParameter("instructionsType", "text")
            .addQueryParameter("language", "en-US")

        if (profile.hazmat) {
            urlBuilder.addQueryParameter("vehicleLoadType", "otherHazmatExplosive")
        }

        val request = Request.Builder().url(urlBuilder.build()).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(RouteResult.Error(e.message ?: "Network error"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body?.string()
                    if (!resp.isSuccessful || body == null) {
                        onResult(RouteResult.Error("Routing request failed (${resp.code})"))
                        return
                    }
                    try {
                        onResult(RouteResult.Success(parseRoute(body)))
                    } catch (e: Exception) {
                        onResult(RouteResult.Error("Could not parse route: ${e.message}"))
                    }
                }
            }
        })
    }

    private fun parseRoute(body: String): TruckRoute {
        val routes = JSONObject(body).getJSONArray("routes")
        if (routes.length() == 0) throw IllegalStateException("No routes in response")
        val route = routes.getJSONObject(0)
        val summary = route.getJSONObject("summary")

        val path = mutableListOf<LatLng>()
        val legs = route.getJSONArray("legs")
        for (i in 0 until legs.length()) {
            val points = legs.getJSONObject(i).getJSONArray("points")
            for (j in 0 until points.length()) {
                val point = points.getJSONObject(j)
                path.add(LatLng(point.getDouble("latitude"), point.getDouble("longitude")))
            }
        }

        val instructions = mutableListOf<RouteInstruction>()
        val guidanceInstructions: JSONArray? = route.optJSONObject("guidance")?.optJSONArray("instructions")
        if (guidanceInstructions != null) {
            for (i in 0 until guidanceInstructions.length()) {
                val instruction = guidanceInstructions.getJSONObject(i)
                val message = instruction.optString("message", "")
                val point = instruction.optJSONObject("point") ?: continue
                if (message.isBlank()) continue
                instructions.add(
                    RouteInstruction(
                        text = message,
                        distanceFromRouteStartMeters = instruction.optDouble("routeOffsetInMeters", 0.0),
                        point = LatLng(point.getDouble("latitude"), point.getDouble("longitude"))
                    )
                )
            }
        }

        val travelTimeSeconds = summary.getLong("travelTimeInSeconds")

        return TruckRoute(
            path = path,
            instructions = instructions,
            distanceMeters = summary.getDouble("lengthInMeters"),
            travelTimeSeconds = travelTimeSeconds,
            trafficDelaySeconds = summary.optLong("trafficDelayInSeconds", 0L),
            arrivalTimeMillis = System.currentTimeMillis() + travelTimeSeconds * 1000
        )
    }
}
