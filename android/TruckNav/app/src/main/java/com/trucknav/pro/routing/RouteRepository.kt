package com.trucknav.pro.routing

import android.content.Context
import com.trucknav.pro.BuildConfig
import com.trucknav.pro.model.LatLng
import com.trucknav.pro.model.RouteInstruction
import com.trucknav.pro.model.TruckProfile
import com.trucknav.pro.model.TruckRoute

// --- TomTom Routing SDK ------------------------------------------------
// Package paths below match the current (2025/2026) "Maps and Navigation SDK
// for Android" layout described at
// https://docs.tomtom.com/navigation/android/guides/routing/planning-a-route
// If Android Studio flags an import as unresolved after Gradle sync, use
// "Optimize imports" / the quick-fix suggestion — TomTom occasionally moves
// classes between sub-packages across releases; the class/field names
// themselves (RoutePlanningOptions, Vehicle.Truck, VehicleDimensions,
// VehicleLoad, HazmatType, RoutePlanningCallback...) are stable.
import com.tomtom.sdk.location.GeoPoint
import com.tomtom.sdk.routing.RoutePlanner
import com.tomtom.sdk.routing.RoutePlanningCallback
import com.tomtom.sdk.routing.RoutePlanningResponse
import com.tomtom.sdk.routing.RoutingFailure
import com.tomtom.sdk.routing.online.OnlineRoutePlanner
import com.tomtom.sdk.routing.options.CostModel
import com.tomtom.sdk.routing.options.Itinerary
import com.tomtom.sdk.routing.options.RoutePlanningOptions
import com.tomtom.sdk.routing.options.RouteType
import com.tomtom.sdk.routing.options.vehicle.HazmatType
import com.tomtom.sdk.routing.options.vehicle.Vehicle
import com.tomtom.sdk.routing.options.vehicle.VehicleDimensions
import com.tomtom.sdk.routing.options.vehicle.VehicleLoad
import com.tomtom.sdk.routing.route.Route

sealed class RouteResult {
    data class Success(val route: TruckRoute) : RouteResult()
    data class Error(val message: String) : RouteResult()
}

/** Plans truck-legal routes (weight/height/length/hazmat restrictions honored) with live traffic. */
class RouteRepository(context: Context) {

    private val planner: RoutePlanner = OnlineRoutePlanner.create(context, BuildConfig.TOMTOM_API_KEY)

    fun planTruckRoute(
        origin: LatLng,
        destination: LatLng,
        profile: TruckProfile,
        onResult: (RouteResult) -> Unit
    ) {
        val vehicle = Vehicle.Truck(
            dimensions = VehicleDimensions(
                weight = profile.weightKg,
                height = profile.heightCm,
                length = profile.lengthCm
            ),
            load = if (profile.hazmat) VehicleLoad(hazmatType = HazmatType.General) else null
        )

        val options = RoutePlanningOptions(
            itinerary = Itinerary(
                origin = GeoPoint(origin.latitude, origin.longitude),
                destination = GeoPoint(destination.latitude, destination.longitude)
            ),
            costModel = CostModel(routeType = RouteType.Fastest),
            vehicle = vehicle
        )

        planner.planRoute(options, object : RoutePlanningCallback {
            override fun onSuccess(result: RoutePlanningResponse) {
                val route = result.routes.firstOrNull()
                if (route == null) {
                    onResult(RouteResult.Error("No truck-legal route found."))
                } else {
                    onResult(RouteResult.Success(toDomainRoute(route)))
                }
            }

            override fun onFailure(failure: RoutingFailure) {
                onResult(RouteResult.Error(failure.message ?: "Routing failed."))
            }
        })
    }

    private fun toDomainRoute(route: Route): TruckRoute {
        val path = route.legs.flatMap { leg -> leg.points.map { LatLng(it.latitude, it.longitude) } }
        val cumulative = RouteGeometry.cumulativeDistances(path)

        val instructions = route.legs.flatMap { leg -> leg.instructions }.mapNotNull { instruction ->
            val point = instruction.point ?: return@mapNotNull null
            val latLng = LatLng(point.latitude, point.longitude)
            RouteInstruction(
                text = instruction.text.orEmpty(),
                distanceFromRouteStartMeters = RouteGeometry.distanceFromStartTo(path, cumulative, latLng),
                point = latLng
            )
        }.filter { it.text.isNotBlank() }

        val summary = route.summary
        val travelTimeSeconds = summary.travelTimeInSeconds.toLong()

        return TruckRoute(
            path = path,
            instructions = instructions,
            distanceMeters = summary.lengthInMeters.toDouble(),
            travelTimeSeconds = travelTimeSeconds,
            trafficDelaySeconds = summary.trafficDelayInSeconds.toLong(),
            arrivalTimeMillis = System.currentTimeMillis() + travelTimeSeconds * 1000
        )
    }
}
