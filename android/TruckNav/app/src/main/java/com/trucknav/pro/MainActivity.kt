package com.trucknav.pro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.snackbar.Snackbar
import com.trucknav.pro.data.RecentDestinationsStore
import com.trucknav.pro.data.TruckProfileStore
import com.trucknav.pro.databinding.ActivityMainBinding
import com.trucknav.pro.location.LocationTracker
import com.trucknav.pro.location.TomTomLocationProviderAdapter
import com.trucknav.pro.model.LatLng
import com.trucknav.pro.model.RouteInstruction
import com.trucknav.pro.model.TrafficSeverity
import com.trucknav.pro.model.TruckProfile
import com.trucknav.pro.model.TruckRoute
import com.trucknav.pro.poi.TruckEntrance
import com.trucknav.pro.poi.TruckEntranceRepository
import com.trucknav.pro.poi.TruckEntranceResult
import com.trucknav.pro.poi.WeighStationRepository
import com.trucknav.pro.poi.WeighStationResult
import com.trucknav.pro.routing.RouteRepository
import com.trucknav.pro.routing.RouteResult
import com.trucknav.pro.search.DestinationCandidate
import com.trucknav.pro.search.DestinationResult
import com.trucknav.pro.search.SearchRepository
import com.trucknav.pro.traffic.TrafficController
import com.trucknav.pro.ui.TruckProfileDialogFragment
import com.trucknav.pro.voice.RouteProgressTracker
import com.trucknav.pro.voice.VoiceGuidanceEngine
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

// --- TomTom Map Display SDK --------------------------------------------
// See the package-path note in RouteRepository.kt: verify these against
// Android Studio's import quick-fix after Gradle sync if unresolved.
import com.tomtom.sdk.location.GeoPoint
import com.tomtom.sdk.map.display.MapOptions
import com.tomtom.sdk.map.display.TomTomMap
import com.tomtom.sdk.map.display.camera.CameraOptions
import com.tomtom.sdk.map.display.gesture.MapPanningListener
import com.tomtom.sdk.map.display.image.ImageFactory
import com.tomtom.sdk.map.display.location.LocationMarkerOptions
import com.tomtom.sdk.map.display.marker.Marker
import com.tomtom.sdk.map.display.marker.MarkerOptions
import com.tomtom.sdk.map.display.route.Route as MapRoute
import com.tomtom.sdk.map.display.route.RouteOptions
import com.tomtom.sdk.map.display.style.LoadingStyleFailure
import com.tomtom.sdk.map.display.style.StandardStyles
import com.tomtom.sdk.map.display.style.StyleLoadingCallback
import com.tomtom.sdk.map.display.ui.MapFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var routeRepository: RouteRepository
    private lateinit var searchRepository: SearchRepository
    private lateinit var weighStationRepository: WeighStationRepository
    private lateinit var truckEntranceRepository: TruckEntranceRepository
    private lateinit var truckProfileStore: TruckProfileStore
    private lateinit var recentDestinationsStore: RecentDestinationsStore
    private lateinit var locationTracker: LocationTracker
    private lateinit var voiceGuidanceEngine: VoiceGuidanceEngine

    private var tomTomMap: TomTomMap? = null
    private var trafficController: TrafficController? = null
    private var locationProviderAdapter: TomTomLocationProviderAdapter? = null

    private var currentPosition = LatLng(34.5828, -117.4093) // fallback: Adelanto, CA
    private var destinationPosition: LatLng? = null
    private var destinationLabel: String? = null

    // Stops visited before the final destination, in order. Position + display label.
    // NOTE: a mid-route reroute (see onRouteDeviated below) currently replans through
    // all of these regardless of which the driver has already passed -- fine for the
    // common case of 1-2 stops on a personal route, but not stop-aware.
    private val waypoints = mutableListOf<Pair<LatLng, String>>()

    private val drawnRouteSegments = mutableListOf<MapRoute>()
    private val drawnWeighStationMarkers = mutableListOf<Marker>()
    private val drawnTruckEntranceMarkers = mutableListOf<Marker>()
    private var plannedRoute: TruckRoute? = null

    private var progressTracker: RouteProgressTracker? = null
    private var isNavigating = false

    // While true, the camera auto-follows the driver's position during navigation.
    // A manual pan gesture turns this off (see the MapPanningListener below) so the
    // driver can look ahead on the map without it snapping back every second; the
    // recenter button turns it back on. Zoom/tilt are intentionally left out of the
    // per-tick camera updates below so pinch-zoom is never overridden either.
    private var isCameraFollowing = true

    private var isSatelliteView = false

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
        setupWindowInsets()

        routeRepository = RouteRepository()
        searchRepository = SearchRepository()
        weighStationRepository = WeighStationRepository()
        truckEntranceRepository = TruckEntranceRepository()
        truckProfileStore = TruckProfileStore(this)
        recentDestinationsStore = RecentDestinationsStore(this)
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

    /**
     * Android 15 (targetSdk 35) draws this app edge-to-edge by default, so the bottom
     * route sheet and the floating active-nav bar would otherwise sit underneath the
     * phone's own back/home/recents bar (3-button nav) or gesture strip. Push them up
     * by however tall that system bar actually is on this device, on top of their
     * existing fixed padding/margin.
     */
    private fun setupWindowInsets() {
        // Make edge-to-edge explicit instead of relying on it being implied by targetSdk 35 --
        // this is what actually makes the window deliver non-zero system bar insets below.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Padding on navSheetContent (a plain LinearLayout), not the navSheet CardView itself --
        // CardView's own padding interacts with its shadow/corner rendering, so push the inset
        // onto ordinary content padding instead.
        val navSheetContentBasePadding = binding.navSheetContent.paddingBottom
        val activeNavBarBaseMargin = (binding.activeNavBottomBar.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBarsInsetBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom

            binding.navSheetContent.updatePadding(bottom = navSheetContentBasePadding + systemBarsInsetBottom)
            binding.activeNavBottomBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = activeNavBarBaseMargin + systemBarsInsetBottom
            }

            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    // ------------------------------------------------------------------
    // Map setup
    // ------------------------------------------------------------------

    private fun onMapReady(map: TomTomMap) {
        tomTomMap = map
        trafficController = TrafficController(map)
        map.moveCamera(CameraOptions(position = currentPosition.toGeoPoint(), zoom = 14.0))
        enableLocationMarkerIfPermitted()

        // A manual drag means the driver wants to look around; stop fighting them
        // with the auto-follow camera updates until they tap the recenter button.
        map.addMapPanningListener(object : MapPanningListener {
            override fun onMapPanningStarted() {
                isCameraFollowing = false
            }
            override fun onMapPanningOngoing() = Unit
            override fun onMapPanningEnded() = Unit
        })
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

        binding.recentButton.setOnClickListener { showRecentDestinations() }
        binding.addStopButton.setOnClickListener { promptAddStop() }

        binding.dimensionsButton.setOnClickListener { showTruckProfileDialog() }
        binding.trafficButton.setOnClickListener { toggleTraffic() }
        binding.satelliteButton.setOnClickListener { toggleSatelliteView() }
        binding.recenterButton.setOnClickListener { recenterCamera() }
        binding.stepsButton.setOnClickListener { showDirectionsList() }
        binding.activeNavOverlay.setOnClickListener { showDirectionsList() }

        binding.startNavButton.setOnClickListener { startNavigation() }
        binding.cancelRouteButton.setOnClickListener { cancelRoute() }
        binding.endNavButton.setOnClickListener { endNavigation() }
    }

    private fun recenterCamera() {
        isCameraFollowing = true
        val zoom = if (isNavigating) 17.5 else 15.0
        val tilt = if (isNavigating) 60.0 else 0.0
        tomTomMap?.moveCamera(CameraOptions(position = currentPosition.toGeoPoint(), zoom = zoom, tilt = tilt))
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

    private fun toggleSatelliteView() {
        val map = tomTomMap ?: return
        isSatelliteView = !isSatelliteView
        map.loadStyle(
            if (isSatelliteView) StandardStyles.SATELLITE else StandardStyles.BROWSING,
            object : StyleLoadingCallback {
                override fun onSuccess() = Unit
                override fun onFailure(failure: LoadingStyleFailure) = Unit
            }
        )
        binding.satelliteButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, if (isSatelliteView) R.color.truck_orange else android.R.color.white)
        )
    }

    // ------------------------------------------------------------------
    // Search + destination picking + stops + recents
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
                    if (result.candidates.size == 1) {
                        setFinalDestination(result.candidates[0])
                    } else {
                        showDestinationPicker(result.candidates) { candidate -> setFinalDestination(candidate) }
                    }
                }
                DestinationResult.NoResults ->
                    Snackbar.make(binding.root, R.string.error_address_not_found, Snackbar.LENGTH_SHORT).show()
                is DestinationResult.Error ->
                    Snackbar.make(binding.root, result.message, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun setFinalDestination(candidate: DestinationCandidate) {
        clearTruckEntranceMarkers()
        destinationPosition = candidate.position
        destinationLabel = candidate.primaryLabel
        binding.destInput.setText(candidate.primaryLabel)
        calculateRoute(truckProfileStore.load())
        checkForTruckEntrance(candidate.position)
    }

    /**
     * Looks up OSM-tagged loading docks / service entrances / HGV gates near the
     * destination and, if one is close enough to plausibly be this address's truck
     * entrance, offers to route there instead of the street address. Most addresses
     * won't have anything tagged -- this stays silent when that's the case rather
     * than nagging on every search.
     */
    private fun checkForTruckEntrance(destination: LatLng) {
        truckEntranceRepository.findNear(destination) { result ->
            // The driver may have already searched something else by the time this
            // (possibly slow/flaky) lookup returns -- don't act on a stale destination.
            if (destinationPosition != destination) return@findNear
            if (result !is TruckEntranceResult.Success) return@findNear

            val map = tomTomMap
            if (map != null) {
                result.entrances.forEach { entrance ->
                    drawnTruckEntranceMarkers += map.addMarker(
                        MarkerOptions(
                            coordinate = entrance.position.toGeoPoint(),
                            pinImage = ImageFactory.fromResource(R.drawable.ic_truck_entrance),
                            balloonText = entrance.kind
                        )
                    )
                }
            }

            val nearest = result.entrances.minByOrNull { it.position.distanceTo(destination) } ?: return@findNear
            if (nearest.position.distanceTo(destination) <= REROUTE_OFFER_RADIUS_METERS) {
                offerRouteToTruckEntrance(nearest, destination)
            }
        }
    }

    private fun offerRouteToTruckEntrance(entrance: TruckEntrance, originalDestination: LatLng) {
        AlertDialog.Builder(this)
            .setTitle("Truck entrance found")
            .setMessage(
                "OpenStreetMap has a marked ${entrance.kind.lowercase(Locale.US)} near this address. " +
                    "Route there instead of the street address?\n\n" +
                    "(This only changes where the pin is -- the route still follows public roads to get there.)"
            )
            .setPositiveButton("Route to entrance") { _, _ ->
                if (destinationPosition == originalDestination) {
                    destinationPosition = entrance.position
                    calculateRoute(truckProfileStore.load())
                }
            }
            .setNegativeButton("Use street address", null)
            .show()
    }

    private fun clearTruckEntranceMarkers() {
        drawnTruckEntranceMarkers.forEach { it.remove() }
        drawnTruckEntranceMarkers.clear()
    }

    private fun promptAddStop() {
        val input = EditText(this).apply {
            hint = "Search for a stop…"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Add a stop")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                val query = input.text?.toString()?.trim().orEmpty()
                if (query.isEmpty()) return@setPositiveButton
                searchRepository.searchDestination(query, currentPosition) { result ->
                    when (result) {
                        is DestinationResult.Success -> {
                            if (result.candidates.size == 1) {
                                addWaypoint(result.candidates[0])
                            } else {
                                showDestinationPicker(result.candidates) { candidate -> addWaypoint(candidate) }
                            }
                        }
                        DestinationResult.NoResults ->
                            Snackbar.make(binding.root, R.string.error_address_not_found, Snackbar.LENGTH_SHORT).show()
                        is DestinationResult.Error ->
                            Snackbar.make(binding.root, result.message, Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addWaypoint(candidate: DestinationCandidate) {
        waypoints.add(candidate.position to candidate.primaryLabel)
        updateWaypointsUi()
        if (destinationPosition != null) {
            calculateRoute(truckProfileStore.load())
        } else {
            Snackbar.make(binding.root, "Stop added. Now enter your final destination.", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun updateWaypointsUi() {
        binding.waypointsContainer.removeAllViews()
        if (waypoints.isEmpty()) {
            binding.waypointsContainer.visibility = View.GONE
            return
        }
        binding.waypointsContainer.visibility = View.VISIBLE

        waypoints.forEachIndexed { index, (_, label) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            row.addView(
                TextView(this).apply {
                    text = "Via: $label"
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            row.addView(
                TextView(this).apply {
                    text = "Remove"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(context, R.color.truck_red))
                    setPadding(24, 0, 0, 0)
                    setOnClickListener {
                        waypoints.removeAt(index)
                        updateWaypointsUi()
                        if (destinationPosition != null) calculateRoute(truckProfileStore.load())
                    }
                }
            )
            binding.waypointsContainer.addView(row)
        }
    }

    private fun showRecentDestinations() {
        val recents = recentDestinationsStore.load()
        if (recents.isEmpty()) {
            Snackbar.make(binding.root, "No recent destinations yet.", Snackbar.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Recent destinations")
            .setItems(recents.map { it.label }.toTypedArray()) { _, which ->
                val chosen = recents[which]
                destinationPosition = chosen.position
                destinationLabel = chosen.label
                binding.destInput.setText(chosen.label)
                calculateRoute(truckProfileStore.load())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Two-line picker (name + address) shared by destination search and add-stop search. */
    private fun showDestinationPicker(candidates: List<DestinationCandidate>, onChosen: (DestinationCandidate) -> Unit) {
        val adapter = object : ArrayAdapter<DestinationCandidate>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, candidates
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val candidate = candidates[position]
                view.findViewById<TextView>(android.R.id.text1).text = candidate.primaryLabel
                view.findViewById<TextView>(android.R.id.text2).text = candidate.secondaryLabel.orEmpty()
                return view
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Choose a destination")
            .setAdapter(adapter) { _, which -> onChosen(candidates[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    // Truck-legal routing
    // ------------------------------------------------------------------

    private fun calculateRoute(profile: TruckProfile) {
        val destination = destinationPosition ?: return

        routeRepository.planTruckRoute(currentPosition, destination, profile, waypoints.map { it.first }) { result ->
            when (result) {
                is RouteResult.Success -> {
                    plannedRoute = result.route
                    drawRoute(result.route)
                    showRouteSummary(result.route)
                    destinationLabel?.let { label -> recentDestinationsStore.record(label, destination) }
                }
                is RouteResult.Error ->
                    Snackbar.make(binding.root, result.message.ifBlank { getString(R.string.error_routing_generic) }, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun drawRoute(route: TruckRoute) {
        val map = tomTomMap ?: return
        clearDrawnRoute()

        // Base line for the whole route, plus the start/end pins.
        drawnRouteSegments += map.addRoute(
            RouteOptions(
                geometry = route.path.map { it.toGeoPoint() },
                departureMarkerVisible = true,
                destinationMarkerVisible = true
            )
        )

        // Congested stretches drawn on top in their own color so traffic severity is
        // visible at a glance, matching what the ETA card's delay callout is warning about.
        for (segment in route.trafficSegments) {
            val start = segment.startIndex.coerceIn(0, route.path.size - 1)
            val end = segment.endIndex.coerceIn(start, route.path.size - 1)
            if (end <= start) continue

            val color = when (segment.severity) {
                TrafficSeverity.MODERATE -> ContextCompat.getColor(this, R.color.traffic_moderate)
                TrafficSeverity.MAJOR -> ContextCompat.getColor(this, R.color.traffic_major)
                TrafficSeverity.CLOSURE -> ContextCompat.getColor(this, R.color.traffic_closure)
                TrafficSeverity.MINOR -> continue
            }

            drawnRouteSegments += map.addRoute(
                RouteOptions(
                    geometry = route.path.subList(start, end + 1).map { it.toGeoPoint() },
                    color = color
                )
            )
        }

        map.zoomToRoutes()
        showWeighStations(route)
    }

    private fun clearDrawnRoute() {
        drawnRouteSegments.forEach { it.remove() }
        drawnRouteSegments.clear()
        drawnWeighStationMarkers.forEach { it.remove() }
        drawnWeighStationMarkers.clear()
    }

    /**
     * Marks weigh station / port-of-entry POI locations near the route. Locations only --
     * TomTom's map data can say a weigh station exists here, but no source (TomTom's or
     * otherwise) publishes live, crowd-verified open/closed status, so this deliberately
     * never claims a station is currently open or closed.
     */
    private fun showWeighStations(route: TruckRoute) {
        weighStationRepository.findAlongRoute(route) { result ->
            val map = tomTomMap ?: return@findAlongRoute
            if (result !is WeighStationResult.Success) return@findAlongRoute
            result.stations.forEach { station ->
                drawnWeighStationMarkers += map.addMarker(
                    MarkerOptions(
                        coordinate = station.position.toGeoPoint(),
                        pinImage = ImageFactory.fromResource(R.drawable.ic_weigh_station),
                        balloonText = station.name
                    )
                )
            }
        }
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

    /** Full turn-by-turn step list for the planned route, opened from the "steps" button or the nav banner. */
    private fun showDirectionsList() {
        val route = plannedRoute
        if (route == null || route.instructions.isEmpty()) {
            Snackbar.make(binding.root, "No turn-by-turn steps available for this route.", Snackbar.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        route.instructions.forEachIndexed { index, instruction ->
            val stepDistanceMeters = if (index == 0) {
                instruction.distanceFromRouteStartMeters
            } else {
                instruction.distanceFromRouteStartMeters - route.instructions[index - 1].distanceFromRouteStartMeters
            }

            container.addView(
                TextView(this).apply {
                    text = if (instruction.isSharpTurn) {
                        "${index + 1}. ⚠ ${instruction.text}"
                    } else {
                        "${index + 1}. ${instruction.text}"
                    }
                    textSize = 15f
                    setPadding(0, 20, 0, 4)
                    if (instruction.isSharpTurn) {
                        setTextColor(ContextCompat.getColor(context, R.color.traffic_major))
                    }
                }
            )
            container.addView(
                TextView(this).apply {
                    text = formatStepDistance(stepDistanceMeters)
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Turn-by-turn directions")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun formatStepDistance(meters: Double): String {
        val miles = meters * METERS_TO_MILES
        return if (miles < 0.1) "${(meters * 3.28084).roundToInt()} ft" else "%.1f mi".format(miles)
    }

    private fun formatDuration(totalMinutes: Int): String =
        if (totalMinutes < 60) "$totalMinutes min" else "${totalMinutes / 60} hr ${totalMinutes % 60} min"

    private fun cancelRoute() {
        clearDrawnRoute()
        clearTruckEntranceMarkers()
        destinationPosition = null
        destinationLabel = null
        plannedRoute = null
        waypoints.clear()
        updateWaypointsUi()

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
        isCameraFollowing = true

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tomTomMap?.moveCamera(
            CameraOptions(position = currentPosition.toGeoPoint(), zoom = 17.5, tilt = 60.0)
        )

        binding.searchCard.visibility = View.GONE
        binding.quickActionsRow.visibility = View.GONE
        binding.navSheet.visibility = View.GONE
        binding.activeNavOverlay.visibility = View.VISIBLE
        binding.activeNavBottomBar.visibility = View.VISIBLE

        attachProgressTracker(route)

        locationTracker.start(intervalMillis = 1000L) { location, bearing ->
            currentPosition = location
            progressTracker?.onLocationUpdate(location)
            // zoom/tilt deliberately omitted here (left null = unchanged) so a pinch-zoom
            // or tilt gesture the driver made isn't reset on the next GPS tick.
            if (isCameraFollowing) {
                tomTomMap?.moveCamera(
                    CameraOptions(position = location.toGeoPoint(), rotation = bearing.toDouble())
                )
            }
        }
    }

    private fun attachProgressTracker(route: TruckRoute) {
        progressTracker = RouteProgressTracker(route, object : RouteProgressTracker.Listener {
            override fun onInstructionChanged(instruction: RouteInstruction) {
                runOnUiThread {
                    binding.navInstructionText.text = instruction.text
                    if (instruction.isSharpTurn) {
                        binding.activeNavOverlay.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.traffic_major))
                        binding.navOverlayIcon.setImageResource(R.drawable.ic_warning)
                    } else {
                        binding.activeNavOverlay.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.truck_green))
                        binding.navOverlayIcon.setImageResource(R.drawable.ic_turn_right)
                    }
                }
            }

            override fun onAnnounce(text: String) {
                voiceGuidanceEngine.speak(text)
            }

            override fun onProgress(distanceRemainingMeters: Double, etaMillis: Long) {
                runOnUiThread {
                    val minutesLeft = (etaMillis - System.currentTimeMillis()).coerceAtLeast(0) / 60000
                    binding.activeEtaText.text = formatDuration(minutesLeft.toInt())
                    val arrivalClock = SimpleDateFormat("h:mm a", Locale.US).format(etaMillis)
                    binding.activeDistText.text = "%.1f mi · %s".format(distanceRemainingMeters * METERS_TO_MILES, arrivalClock)
                }
            }

            override fun onArrived() {
                runOnUiThread {
                    voiceGuidanceEngine.speak("You have arrived at your destination.")
                    endNavigation()
                }
            }

            override fun onRouteDeviated() {
                runOnUiThread { rerouteFromCurrentPosition() }
            }
        })
    }

    private fun rerouteFromCurrentPosition() {
        if (!isNavigating) return
        val destination = destinationPosition ?: return

        voiceGuidanceEngine.speak("Recalculating route.")
        routeRepository.planTruckRoute(currentPosition, destination, truckProfileStore.load(), waypoints.map { it.first }) { result ->
            when (result) {
                is RouteResult.Success -> {
                    plannedRoute = result.route
                    drawRoute(result.route)
                    attachProgressTracker(result.route)
                }
                is RouteResult.Error ->
                    Snackbar.make(binding.root, "Reroute failed: ${result.message}", Snackbar.LENGTH_LONG).show()
            }
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
        const val REROUTE_OFFER_RADIUS_METERS = 200.0
    }
}
