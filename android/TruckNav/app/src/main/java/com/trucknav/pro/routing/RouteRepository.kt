package com.trucknav.pro.routing

import android.os.Handler
import android.os.Looper
import com.trucknav.pro.BuildConfig
import com.trucknav.pro.model.LatLng
import com.trucknav.pro.model.RouteInstruction
import com.trucknav.pro.model.TrafficSegment
import com.trucknav.pro.model.TrafficSeverity
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
    private val mainHandler = Handler(Looper.getMainLooper())

    fun planTruckRoute(
        origin: LatLng,
        destination: LatLng,
        profile: TruckProfile,
        waypoints: List<LatLng> = emptyList(),
        onResult: (RouteResult) -> Unit
    ) {
        // OkHttp callbacks fire on a background thread, but callers (drawing the
        // route on TomTomMap, updating views) need the main thread -- hop back to it.
        val deliver: (RouteResult) -> Unit = { result -> mainHandler.post { onResult(result) } }

        // TomTom's calculateRoute endpoint takes any number of colon-separated
        // "lat,lon" stops in order: origin:stop1:stop2:...:destination.
        val stops = (listOf(origin) + waypoints + destination)
            .joinToString(":") { "${it.latitude},${it.longitude}" }
        val path = "https://api.tomtom.com/routing/1/calculateRoute/$stops/json"

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
            .addQueryParameter("sectionType", "traffic")

        if (profile.hazmat) {
            urlBuilder.addQueryParameter("vehicleLoadType", "otherHazmatExplosive")
        }

        val request = Request.Builder().url(urlBuilder.build()).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                deliver(RouteResult.Error(e.message ?: "Network error"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body?.string()
                    if (!resp.isSuccessful || body == null) {
                        deliver(RouteResult.Error("Routing request failed (${resp.code})"))
                        return
                    }
                    val parsed = try {
                        RouteResult.Success(parseRoute(body))
                    } catch (e: Exception) {
                        RouteResult.Error("Could not parse route: ${e.message}")
                    }
                    deliver(parsed)
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

                // maneuver is a code like "SHARP_LEFT" / "MAKE_UTURN" / "TRY_MAKE_UTURN"; when it's
                // missing, fall back to the raw turn angle (-180 = U-turn, beyond +/-135 = a sharp
                // turn even if not flagged as one). Neither field carries grade/elevation data, so
                // this can only flag turn geometry, not steep grades or runaway-truck ramps.
                val maneuver = instruction.optString("maneuver").takeIf { it.isNotBlank() }
                val turnAngle = instruction.optDouble("turnAngleInDecimalDegrees", Double.NaN)
                val isSharpTurn = maneuver in SHARP_MANEUVERS ||
                    (!turnAngle.isNaN() && kotlin.math.abs(turnAngle) >= SHARP_TURN_ANGLE_DEGREES)

                instructions.add(
                    RouteInstruction(
                        text = message,
                        distanceFromRouteStartMeters = instruction.optDouble("routeOffsetInMeters", 0.0),
                        point = LatLng(point.getDouble("latitude"), point.getDouble("longitude")),
                        maneuver = maneuver,
                        isSharpTurn = isSharpTurn
                    )
                )
            }
        }

        val travelTimeSeconds = summary.getLong("travelTimeInSeconds")

        val trafficSegments = mutableListOf<TrafficSegment>()
        val sections = route.optJSONArray("sections")
        if (sections != null) {
            for (i in 0 until sections.length()) {
                val section = sections.getJSONObject(i)
                if (section.optString("sectionType") != "TRAFFIC") continue
                val start = section.optInt("startPointIndex", -1)
                val end = section.optInt("endPointIndex", -1)
                if (start < 0 || end <= start) continue
                // magnitudeOfDelay: 0=unknown, 1=minor, 2=moderate, 3=major, 4=closure/indefinite.
                val severity = when (section.optInt("magnitudeOfDelay", 0)) {
                    2 -> TrafficSeverity.MODERATE
                    3 -> TrafficSeverity.MAJOR
                    4 -> TrafficSeverity.CLOSURE
                    else -> null // skip unknown/minor delays -- not worth highlighting on the map
                }
                if (severity != null) {
                    trafficSegments.add(TrafficSegment(start, end, severity))
                }
            }
        }

        return TruckRoute(
            path = path,
            instructions = instructions,
            distanceMeters = summary.getDouble("lengthInMeters"),
            travelTimeSeconds = travelTimeSeconds,
            trafficDelaySeconds = summary.optLong("trafficDelayInSeconds", 0L),
            arrivalTimeMillis = System.currentTimeMillis() + travelTimeSeconds * 1000,
            trafficSegments = trafficSegments
        )
    }

    private companion object {
        val SHARP_MANEUVERS = setOf("SHARP_LEFT", "SHARP_RIGHT", "MAKE_UTURN", "TRY_MAKE_UTURN")
        const val SHARP_TURN_ANGLE_DEGREES = 135.0
    }
}
