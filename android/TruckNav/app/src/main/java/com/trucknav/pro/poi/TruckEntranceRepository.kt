package com.trucknav.pro.poi

import android.os.Handler
import android.os.Looper
import com.trucknav.pro.model.LatLng
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos

/**
 * A truck-specific access point near a destination -- a marked loading dock, a
 * back/delivery entrance, or a gate explicitly tagged for heavy-goods-vehicle
 * access -- sourced from OpenStreetMap, the only map data source that tags this
 * at all (TomTom/Google don't). Coverage is real but geographically uneven --
 * well-mapped logistics hubs return several hits, most addresses return none.
 */
data class TruckEntrance(val position: LatLng, val kind: String)

sealed class TruckEntranceResult {
    data class Success(val entrances: List<TruckEntrance>) : TruckEntranceResult()
    object NotFound : TruckEntranceResult()
}

/**
 * Looks up OSM-tagged truck entrances near a destination via the public Overpass
 * API (https://overpass-api.de). This only changes *which point on the map* the
 * app treats as the destination -- it does not (and cannot, without running a
 * custom routing engine fed the full OSM road graph including private service
 * roads) make TomTom's truck router actually path-find across a property's
 * internal service roads. Pointing the destination at the dock/gate node instead
 * of the building's front-door address is the practical result.
 */
class TruckEntranceRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The public Overpass API is unauthenticated and frequently overloaded/flaky, so
     * this tries a couple of mirrors before giving up -- and always resolves to
     * NotFound rather than surfacing an error, since this is a nice-to-have layered
     * on top of normal routing, never something that should block or nag about it.
     */
    fun findNear(destination: LatLng, radiusMeters: Double = 250.0, onResult: (TruckEntranceResult) -> Unit) {
        val deliver: (TruckEntranceResult) -> Unit = { result -> mainHandler.post { onResult(result) } }
        val bbox = bboxAround(destination, radiusMeters)
        val query = """
            [out:json][timeout:20];
            (
              node["amenity"="loading_dock"]($bbox);
              way["amenity"="loading_dock"]($bbox);
              node["entrance"="service"]($bbox);
              node["routing:entrance"]($bbox);
              node["barrier"="gate"]["hgv"="yes"]($bbox);
            );
            out center tags;
        """.trimIndent()

        tryEndpoint(OVERPASS_ENDPOINTS, 0, query, deliver)
    }

    private fun tryEndpoint(endpoints: List<String>, index: Int, query: String, deliver: (TruckEntranceResult) -> Unit) {
        if (index >= endpoints.size) {
            deliver(TruckEntranceResult.NotFound)
            return
        }
        val body = query.toRequestBody("text/plain".toMediaType())
        val request = Request.Builder().url(endpoints[index]).post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                tryEndpoint(endpoints, index + 1, query, deliver)
            }

            override fun onResponse(call: Call, response: Response) {
                val entrances = response.use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val responseBody = resp.body?.string() ?: return@use null
                    try {
                        parseElements(responseBody)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (entrances == null) {
                    tryEndpoint(endpoints, index + 1, query, deliver)
                } else if (entrances.isEmpty()) {
                    deliver(TruckEntranceResult.NotFound)
                } else {
                    deliver(TruckEntranceResult.Success(entrances))
                }
            }
        })
    }

    private fun parseElements(body: String): List<TruckEntrance> {
        val elements = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        val results = mutableListOf<TruckEntrance>()
        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            val tags = element.optJSONObject("tags") ?: JSONObject()

            val lat: Double
            val lon: Double
            if (element.has("lat") && element.has("lon")) {
                lat = element.getDouble("lat")
                lon = element.getDouble("lon")
            } else {
                val center = element.optJSONObject("center") ?: continue
                lat = center.getDouble("lat")
                lon = center.getDouble("lon")
            }

            val kind = when {
                tags.optString("amenity") == "loading_dock" -> "Loading dock"
                tags.optString("entrance") == "service" -> "Service/delivery entrance"
                tags.has("routing:entrance") -> "Marked truck entrance"
                tags.optString("barrier") == "gate" && tags.optString("hgv") == "yes" -> "HGV-access gate"
                else -> continue
            }
            results.add(TruckEntrance(LatLng(lat, lon), kind))
        }
        return results
    }

    /** "south,west,north,east" box of side ~2*radiusMeters around [center], for Overpass's bbox filter. */
    private fun bboxAround(center: LatLng, radiusMeters: Double): String {
        val latDelta = radiusMeters / 111_320.0
        val cosLat = cos(center.latitude * PI / 180.0).coerceAtLeast(0.01)
        val lonDelta = radiusMeters / (111_320.0 * cosLat)
        val south = center.latitude - latDelta
        val north = center.latitude + latDelta
        val west = center.longitude - lonDelta
        val east = center.longitude + lonDelta
        return "$south,$west,$north,$east"
    }

    private companion object {
        val OVERPASS_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )
    }
}
