package com.trucknav.pro.poi

import android.os.Handler
import android.os.Looper
import com.trucknav.pro.BuildConfig
import com.trucknav.pro.model.LatLng
import com.trucknav.pro.model.TruckRoute
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/**
 * A weigh station / port-of-entry POI location near a planned route.
 *
 * This is a *location* only. There is no live open/closed status here -- no API
 * (TomTom's or otherwise) publishes real-time, crowd-verified weigh station status,
 * so callers must never present these as "open" or "closed", only as "there is a
 * weigh station near here."
 */
data class WeighStation(val position: LatLng, val name: String)

sealed class WeighStationResult {
    data class Success(val stations: List<WeighStation>) : WeighStationResult()
    data class Error(val message: String) : WeighStationResult()
}

/**
 * Looks up weigh station / port-of-entry POIs near a planned route via TomTom's
 * public Fuzzy Search REST API (the same endpoint SearchRepository uses), sampling
 * a handful of points along the route corridor since a single lat/lon+radius search
 * can't cover a long haul.
 */
class WeighStationRepository {

    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun findAlongRoute(route: TruckRoute, onResult: (WeighStationResult) -> Unit) {
        val deliver: (WeighStationResult) -> Unit = { result -> mainHandler.post { onResult(result) } }

        val samplePoints = sampleRoute(route.path, SAMPLE_COUNT)
        if (samplePoints.isEmpty()) {
            deliver(WeighStationResult.Success(emptyList()))
            return
        }

        val merged = mutableMapOf<String, WeighStation>()
        var pending = samplePoints.size
        var anySucceeded = false
        val lock = Any()

        fun finishOne(succeeded: Boolean) {
            synchronized(lock) {
                if (succeeded) anySucceeded = true
                pending--
                if (pending > 0) return
            }
            deliver(
                if (merged.isNotEmpty() || anySucceeded) {
                    WeighStationResult.Success(merged.values.toList())
                } else {
                    WeighStationResult.Error("Could not look up weigh stations")
                }
            )
        }

        samplePoints.forEach { point ->
            val url = "https://api.tomtom.com/search/2/search/weigh%20station.json".toHttpUrl()
                .newBuilder()
                .addQueryParameter("key", BuildConfig.TOMTOM_API_KEY)
                .addQueryParameter("lat", point.latitude.toString())
                .addQueryParameter("lon", point.longitude.toString())
                .addQueryParameter("radius", SEARCH_RADIUS_METERS.toString())
                .addQueryParameter("limit", "10")
                .build()

            client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    finishOne(succeeded = false)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val body = resp.body?.string()
                        val results = if (resp.isSuccessful && body != null) {
                            JSONObject(body).optJSONArray("results")
                        } else {
                            null
                        }
                        if (results != null) {
                            for (i in 0 until results.length()) {
                                val result = results.getJSONObject(i)
                                val position = result.optJSONObject("position") ?: continue
                                val name = result.optJSONObject("poi")?.optString("name")
                                    ?.takeIf { it.isNotBlank() } ?: continue
                                // The fuzzy-text match can surface unrelated nearby POIs;
                                // keep only results that actually read like a weigh station.
                                if (WEIGH_STATION_KEYWORDS.none { name.contains(it, ignoreCase = true) }) continue

                                val lat = position.getDouble("lat")
                                val lon = position.getDouble("lon")
                                val key = "%.3f,%.3f".format(lat, lon)
                                synchronized(lock) { merged[key] = WeighStation(LatLng(lat, lon), name) }
                            }
                        }
                    }
                    finishOne(succeeded = true)
                }
            })
        }
    }

    /** Picks up to [samples] points spread evenly along [path]'s vertex list. */
    private fun sampleRoute(path: List<LatLng>, samples: Int): List<LatLng> {
        if (path.isEmpty()) return emptyList()
        if (path.size <= samples) return path
        val lastIndex = path.size - 1
        val step = (samples - 1).coerceAtLeast(1)
        return (0 until samples).map { i -> path[i * lastIndex / step] }.distinct()
    }

    private companion object {
        const val SAMPLE_COUNT = 4
        const val SEARCH_RADIUS_METERS = 40_000
        val WEIGH_STATION_KEYWORDS = listOf("weigh station", "weigh stn", "port of entry", "inspection station")
    }
}
