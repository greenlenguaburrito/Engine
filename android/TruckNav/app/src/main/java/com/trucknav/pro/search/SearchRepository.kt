package com.trucknav.pro.search

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

sealed class DestinationResult {
    data class Success(val position: LatLng, val label: String) : DestinationResult()
    object NoResults : DestinationResult()
    data class Error(val message: String) : DestinationResult()
}

/**
 * Resolves a free-text destination (address, POI, city) to coordinates, biased
 * toward the driver's current position, via TomTom's public Fuzzy Search REST
 * API (https://api.tomtom.com/search/2/search). See the note in
 * app/build.gradle.kts for why this uses the REST API rather than the native
 * Search SDK.
 */
class SearchRepository {

    private val client = OkHttpClient()

    fun searchDestination(query: String, near: LatLng, onResult: (DestinationResult) -> Unit) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.tomtom.com/search/2/search/$encodedQuery.json".toHttpUrl()
            .newBuilder()
            .addQueryParameter("key", BuildConfig.TOMTOM_API_KEY)
            .addQueryParameter("lat", near.latitude.toString())
            .addQueryParameter("lon", near.longitude.toString())
            .addQueryParameter("limit", "1")
            .build()

        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(DestinationResult.Error(e.message ?: "Network error"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body?.string()
                    if (!resp.isSuccessful || body == null) {
                        onResult(DestinationResult.Error("Search request failed (${resp.code})"))
                        return
                    }
                    val results = JSONObject(body).optJSONArray("results")
                    if (results == null || results.length() == 0) {
                        onResult(DestinationResult.NoResults)
                        return
                    }
                    val first = results.getJSONObject(0)
                    val position = first.getJSONObject("position")
                    val label = first.optJSONObject("address")?.optString("freeformAddress")
                        ?.takeIf { it.isNotBlank() }
                        ?: first.optJSONObject("poi")?.optString("name")
                        ?: query
                    onResult(
                        DestinationResult.Success(
                            LatLng(position.getDouble("lat"), position.getDouble("lon")),
                            label
                        )
                    )
                }
            }
        })
    }
}
