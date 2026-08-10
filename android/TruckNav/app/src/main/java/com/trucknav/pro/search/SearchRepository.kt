package com.trucknav.pro.search

import android.os.Handler
import android.os.Looper
import com.trucknav.pro.BuildConfig
import com.trucknav.pro.model.LatLng
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** One matching place for a search query, e.g. one of several "Amazon" locations nearby. */
data class DestinationCandidate(
    val position: LatLng,
    val primaryLabel: String,
    val secondaryLabel: String?
)

sealed class DestinationResult {
    data class Success(val candidates: List<DestinationCandidate>) : DestinationResult()
    object NoResults : DestinationResult()
    data class Error(val message: String) : DestinationResult()
}

/**
 * Resolves a free-text destination (address, POI, city) to coordinates, biased
 * toward the driver's current position, via TomTom's public Fuzzy Search REST
 * API (https://api.tomtom.com/search/2/search). See the note in
 * app/build.gradle.kts for why this uses the REST API rather than the native
 * Search SDK. Returns multiple candidates -- a query like "Amazon" usually
 * matches several nearby locations -- so the caller can let the driver pick.
 */
class SearchRepository {

    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun searchDestination(query: String, near: LatLng, onResult: (DestinationResult) -> Unit) {
        // OkHttp callbacks fire on a background thread, but callers (Snackbar, then
        // route planning which touches TomTomMap) need the main thread -- hop back to it.
        val deliver: (DestinationResult) -> Unit = { result -> mainHandler.post { onResult(result) } }

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.tomtom.com/search/2/search/$encodedQuery.json".toHttpUrl()
            .newBuilder()
            .addQueryParameter("key", BuildConfig.TOMTOM_API_KEY)
            .addQueryParameter("lat", near.latitude.toString())
            .addQueryParameter("lon", near.longitude.toString())
            .addQueryParameter("limit", "8")
            .build()

        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                deliver(DestinationResult.Error(e.message ?: "Network error"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body?.string()
                    if (!resp.isSuccessful || body == null) {
                        deliver(DestinationResult.Error("Search request failed (${resp.code})"))
                        return
                    }
                    val results = JSONObject(body).optJSONArray("results")
                    if (results == null || results.length() == 0) {
                        deliver(DestinationResult.NoResults)
                        return
                    }

                    val candidates = mutableListOf<DestinationCandidate>()
                    for (i in 0 until results.length()) {
                        val result = results.getJSONObject(i)
                        val position = result.optJSONObject("position") ?: continue
                        val address = result.optJSONObject("address")?.optString("freeformAddress")
                            ?.takeIf { it.isNotBlank() }
                        val poiName = result.optJSONObject("poi")?.optString("name")?.takeIf { it.isNotBlank() }

                        val primary = poiName ?: address ?: query
                        val secondary = if (poiName != null) address else null

                        candidates.add(
                            DestinationCandidate(
                                position = LatLng(position.getDouble("lat"), position.getDouble("lon")),
                                primaryLabel = primary,
                                secondaryLabel = secondary
                            )
                        )
                    }

                    if (candidates.isEmpty()) {
                        deliver(DestinationResult.NoResults)
                    } else {
                        deliver(DestinationResult.Success(candidates))
                    }
                }
            }
        })
    }
}
