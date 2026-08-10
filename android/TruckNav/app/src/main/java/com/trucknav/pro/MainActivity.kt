package com.trucknav.pro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.trucknav.pro.data.TruckProfileStore
import com.trucknav.pro.databinding.ActivityMainBinding
import com.trucknav.pro.location.LocationTracker
import com.trucknav.pro.location.TomTomLocationProviderAdapter
import com.trucknav.pro.model.LatLng
import com.trucknav.pro.model.RouteInstruction
import com.trucknav.pro.model.TruckProfile
import com.trucknav.pro.model.TruckRoute
import com.trucknav.pro.routing.RouteRepository
import com.trucknav.pro.routing.RouteResult
import com.trucknav.pro.search.DestinationResult
import com.trucknav.pro.search.SearchRepository
import com.trucknav.pro.traffic.TrafficController
import com.trucknav.pro.ui.TruckProfileDialogFragment
import com.trucknav.pro.voice.RouteProgressTracker
import com.trucknav.pro.voice.VoiceGuidanceEngine
import java.text.SimpleDateFormat
import java.util.Locale

// --- TomTom Map Display SDK --------------------------------------------
// See the package-path note in RouteRepository.kt: verify these against
// Android Studio's import quick-fix after Gradle sync if unresolved.
import com.tomtom.sdk.location.GeoPoint
import com.tomtom.sdk.map.display.MapOptions
import com.tomtom.sdk.map.display.TomTomMap
import com.tomtom.sdk.map.display.camera.CameraOptions
import com.tomtom.sdk.map.display.location.LocationMarkerOptions
import com.tomtom.sdk.map.display.route.Route as MapRoute
import com.tomtom.sdk.map.display.route.RouteOptions
import com.tomtom.sdk.map.display.ui.MapFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var routeRepository: RouteRepository
    private lateinit var searchRepository: SearchRepository
    private lateinit var truckProfileStore: TruckProfileStore
    private lateinit var locationTracker: LocationTracker
    private lateinit var voiceGuidanceEngine: VoiceGuidanceEngine

    private var tomTomMap: TomTomMap? = null
    private var trafficController: TrafficController? = null
    private var locationProviderAdapter: TomTomLocationProviderAdapter? = null

    private var currentPosition = LatLng(34.5828, -117.4093) // fallback: Adelanto, CA
    private var destinationPosition: LatLng? = null
    private var drawnRoute: MapRoute? = null
    private var plannedRoute: TruckRoute? = null

    private var progressTracker: RouteProgressTracker? = null
    private var isNavigating = false

    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                centerOnDeviceLocation()
                enableLocationMarkerIfPermitted()
            } else {
                binding.originText.text = getString(R.string.permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        routeRepository = RouteRepository()
        searchRepository = SearchRepository()
        truckProfileStore = TruckProfileStore(this)
        locationTracker = LocationTracker(this)
        voiceGuidanceEngine = VoiceGuidanceEngine(this)

        val mapFragment = MapFragment.newInstance(MapOptions(mapKey = BuildConfig.TOMTOM_API_KEY))
        supportFragmentManager.beginTransaction()
            .replace(R.id.mapFragmentContainer, mapFragment)
            .commitNow()
        mapFragment.getMapAsync { map -> onMapReady(map) }

        setupListeners()
        ensureLocationPermission()
    }

    // ------------------------------------------------------------------
    // Map setup
    // ------------------------------------------------------------------

    private fun onMapReady(map: TomTomMap) {
        tomTomMap = map
        trafficController = TrafficController(map)
        map.moveCamera(CameraOptions(position = currentPosition.toGeoPoint(), zoom = 14.0))
        enableLocationMarkerIfPermitted()
    }

    /** Shows the "you are here" dot on the map. Needs both the map and location permission ready. */
    private fun enableLocationMarkerIfPermitted() {
        if (locationProviderAdapter != null) return
        val map = tomTomMap ?: return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val adapter = TomTomLocationProviderAdapter(this)
        locationProviderAdapter = adapter
        map.setLocationProvider(adapter)
        map.enableLocationMarker(LocationMarkerOptions(type = LocationMarkerOptions.Type.Pointer))
        adapter.enable()
    }

    private fun ensureLocationPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            centerOnDeviceLocation()
            enableLocationMarkerIfPermitted()
        } else {
            binding.originText.setText(R.string.detecting_location)
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun centerOnDeviceLocation() {
        locationTracker.requestOnce(
            onLocation = { location ->
                currentPosition = location
                tomTomMap?.moveCamera(CameraOptions(position = location.toGeoPoint(), zoom = 15.0))
                binding.originText.text = getString(R.string.current_location)
            },
            onFailure = {
                binding.originText.text = getString(R.string.current_location)
            }
        )
    }

    // ------------------------------------------------------------------
    // UI wiring
    // ------------------------------------------------------------------

    private fun setupListeners() {
        binding.searchButton.setOnClickListener { searchDestination() }
        binding.destInput.setOnEditorActionListener { _, _, _ -> searchDestination(); true }

        binding.dimensionsButton.setOnClickListener { showTruckProfileDialog() }
        binding.trafficButton.setOnClickListener { toggleTraffic() }
        binding.recenterButton.setOnClickListener {
            tomTomMap?.moveCamera(CameraOptions(position = currentPosition.toGeoPoint(), zoom = 15.0, tilt = 0.0))
        }

        binding.startNavButton.setOnClickListener { startNavigation() }
        binding.cancelRouteButton.setOnClickListener { cancelRoute() }
        binding.endNavButton.setOnClickListener { endNavigation() }
    }

    private fun showTruckProfileDialog() {
        val dialog = TruckProfileDialogFragment()
        dialog.onProfileSaved = { profile ->
            if (destinationPosition != null) {
                calculateRoute(profile)
            } else {
                Snackbar.make(binding.root, "Truck parameters saved.", Snackbar.LENGTH_SHORT).show()
            }
        }
        dialog.show(supportFragmentManager, "truck_profile")
    }

    private fun toggleTraffic() {
        val enabled = trafficController?.toggle() ?: false
        binding.trafficButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, if (enabled) R.color.truck_orange else android.R.color.white)
        )
        val message = if (enabled) "Live traffic & incidents enabled" else "Live traffic disabled"
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------
    // Search + truck-legal routing
    // ------------------------------------------------------------------

    private fun searchDestination() {
        val query = binding.destInput.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            Snackbar.make(binding.root, R.string.error_enter_destination, Snackbar.LENGTH_SHORT).show()
            return
        }

        Snackbar.make(binding.root, R.string.querying_route, Snackbar.LENGTH_SHORT).show()

        searchRepository.searchDestination(query, currentPosition) { result ->
            when (result) {
                is DestinationResult.Success -> {
                    destinationPosition = result.position
                    calculateRoute(truckProfileStore.load())
                }
                DestinationResult.NoResults ->
                    Snackbar.make(binding.root, R.string.error_address_not_found, Snackbar.LENGTH_SHORT).show()
                is DestinationResult.Error ->
                    Snackbar.make(binding.root, result.message, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun calculateRoute(profile: TruckProfile) {
        val destination = destinationPosition ?: return

        routeRepository.planTruckRoute(currentPosition, destination, profile) { result ->
            when (result) {
                is RouteResult.Success -> {
                    plannedRoute = result.route
                    drawRoute(result.route)
                    showRouteSummary(result.route)
                }
                is RouteResult.Error ->
                    Snackbar.make(binding.root, result.message.ifBlank { getString(R.string.error_routing_generic) }, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun drawRoute(route: TruckRoute) {
        val map = tomTomMap ?: return
        drawnRoute?.remove()

        drawnRoute = map.addRoute(
            RouteOptions(
                geometry = route.path.map { it.toGeoPoint() },
                departureMarkerVisible = true,
                destinationMarkerVisible = true
            )
        )
        map.zoomToRoutes()
    }

    private fun showRouteSummary(route: TruckRoute) {
        binding.idleState.visibility = View.GONE
        binding.routeDetails.visibility = View.VISIBLE

        val minutes = (route.travelTimeSeconds / 60).toInt()
        binding.etaText.text = formatDuration(minutes)

        val miles = route.distanceMeters * METERS_TO_MILES
        val arrival = SimpleDateFormat("h:mm a", Locale.US).format(route.arrivalTimeMillis)
        binding.distanceText.text = "%.1f miles • %s arrival".format(miles, arrival)

        if (route.trafficDelaySeconds > 120) {
            binding.etaText.setTextColor(ContextCompat.getColor(this, R.color.truck_red))
            binding.delayText.text = "+${route.trafficDelaySeconds / 60} min traffic delay"
        } else {
            binding.etaText.setTextColor(ContextCompat.getColor(this, R.color.truck_green))
            binding.delayText.setText(R.string.route_clear)
        }
    }

    private fun formatDuration(totalMinutes: Int): String =
        if (totalMinutes < 60) "$totalMinutes min" else "${totalMinutes / 60} hr ${totalMinutes % 60} min"

    private fun cancelRoute() {
        drawnRoute?.remove()
        drawnRoute = null
        destinationPosition = null
        plannedRoute = null

        binding.destInput.text?.clear()
        binding.idleState.visibility = View.VISIBLE
        binding.routeDetails.visibility = View.GONE

        tomTomMap?.moveCamera(CameraOptions(position = currentPosition.toGeoPoint(), zoom = 15.0))
    }

    // ------------------------------------------------------------------
    // Turn-by-turn voice navigation
    // ------------------------------------------------------------------

    private fun startNavigation() {
        val route = plannedRoute ?: return
        isNavigating = true

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tomTomMap?.moveCamera(
            CameraOptions(position = currentPosition.toGeoPoint(), zoom = 17.5, tilt = 60.0)
        )

        binding.searchCard.visibility = View.GONE
        binding.quickActionsRow.visibility = View.GONE
        binding.recenterButton.visibility = View.GONE
        binding.navSheet.visibility = View.GONE
        binding.activeNavOverlay.visibility = View.VISIBLE
        binding.activeNavBottomBar.visibility = View.VISIBLE

        progressTracker = RouteProgressTracker(route, object : RouteProgressTracker.Listener {
            override fun onInstructionChanged(instruction: RouteInstruction) {
                runOnUiThread { binding.navInstructionText.text = instruction.text }
            }

            override fun onAnnounce(text: String) {
                voiceGuidanceEngine.speak(text)
            }

            override fun onProgress(distanceRemainingMeters: Double, etaMillis: Long) {
                runOnUiThread {
                    val minutesLeft = (etaMillis - System.currentTimeMillis()).coerceAtLeast(0) / 60000
                    binding.activeEtaText.text = formatDuration(minutesLeft.toInt())
                    binding.activeDistText.text = "%.1f mi".format(distanceRemainingMeters * METERS_TO_MILES)
                }
            }

            override fun onArrived() {
                runOnUiThread {
                    voiceGuidanceEngine.speak("You have arrived at your destination.")
                    endNavigation()
                }
            }
        })

        locationTracker.start(intervalMillis = 1000L) { location, bearing ->
            currentPosition = location
            progressTracker?.onLocationUpdate(location)
            tomTomMap?.moveCamera(
                CameraOptions(position = location.toGeoPoint(), zoom = 17.5, tilt = 60.0, rotation = bearing.toDouble())
            )
        }
    }

    private fun endNavigation() {
        isNavigating = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        locationTracker.stop()
        voiceGuidanceEngine.stopSpeaking()
        progressTracker = null

        tomTomMap?.moveCamera(CameraOptions(position = currentPosition.toGeoPoint(), zoom = 15.0, tilt = 0.0))

        binding.activeNavOverlay.visibility = View.GONE
        binding.activeNavBottomBar.visibility = View.GONE
        binding.searchCard.visibility = View.VISIBLE
        binding.quickActionsRow.visibility = View.VISIBLE
        binding.recenterButton.visibility = View.VISIBLE
        binding.navSheet.visibility = View.VISIBLE

        cancelRoute()
    }

    override fun onDestroy() {
        super.onDestroy()
        locationTracker.stop()
        locationProviderAdapter?.close()
        voiceGuidanceEngine.shutdown()
    }

    private fun LatLng.toGeoPoint() = GeoPoint(latitude, longitude)

    private companion object {
        const val METERS_TO_MILES = 0.000621371
    }
}
