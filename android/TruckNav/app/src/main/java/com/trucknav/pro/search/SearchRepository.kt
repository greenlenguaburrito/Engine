package com.trucknav.pro.search

import android.content.Context
import com.trucknav.pro.BuildConfig
import com.trucknav.pro.model.LatLng

// See the note in RouteRepository.kt about TomTom package paths possibly
// shifting between SDK releases — Android Studio's import quick-fix will
// resolve the current location for these classes after Gradle sync.
import com.tomtom.sdk.location.GeoPoint
import com.tomtom.sdk.search.Search
import com.tomtom.sdk.search.SearchCallback
import com.tomtom.sdk.search.SearchFailure
import com.tomtom.sdk.search.SearchResponse
import com.tomtom.sdk.search.online.OnlineSearch
import com.tomtom.sdk.search.online.OnlineSearchFuzzySearchOptions

sealed class DestinationResult {
    data class Success(val position: LatLng, val label: String) : DestinationResult()
    object NoResults : DestinationResult()
    data class Error(val message: String) : DestinationResult()
}

/** Resolves a free-text destination (address, POI, city) to coordinates, biased toward the driver's current position. */
class SearchRepository(context: Context) {

    private val search: Search = OnlineSearch.create(context, BuildConfig.TOMTOM_API_KEY)

    fun searchDestination(query: String, near: LatLng, onResult: (DestinationResult) -> Unit) {
        val options = OnlineSearchFuzzySearchOptions(
            query = query,
            geoBias = GeoPoint(near.latitude, near.longitude)
        )

        search.search(options, object : SearchCallback {
            override fun onSuccess(result: SearchResponse) {
                val first = result.results.firstOrNull()
                if (first == null) {
                    onResult(DestinationResult.NoResults)
                    return
                }
                val coordinate = first.place.coordinate
                val label = first.place.address?.freeformAddress ?: first.place.name ?: query
                onResult(DestinationResult.Success(LatLng(coordinate.latitude, coordinate.longitude), label))
            }

            override fun onFailure(failure: SearchFailure) {
                onResult(DestinationResult.Error(failure.message ?: "Search failed."))
            }
        })
    }
}
