package com.newfuel.fuelalert.trip

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.newfuel.fuelalert.BuildConfig
import com.newfuel.fuelalert.FuelAlertApplication
import com.newfuel.fuelalert.alert.AlertCandidate
import com.newfuel.fuelalert.alert.AlertEngine
import com.newfuel.fuelalert.alert.FullScreenAlertNotifier
import com.newfuel.fuelalert.backend.BackendResult
import com.newfuel.fuelalert.backend.PriceFetcher
import com.newfuel.fuelalert.backend.RouteResult
import com.newfuel.fuelalert.backend.StationPrice
import com.newfuel.fuelalert.route.LatLon
import com.newfuel.fuelalert.route.RouteMatcher
import com.newfuel.fuelalert.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * UNVERIFIED BEYOND CAREFUL REVIEW - this file could NOT be compiled in
 * this sandbox, confirmed by actually trying: com.google.android.gms.*
 * (Play Services) is published only on Google's Maven repo, and
 * BuildConfig is generated only by a real Gradle/AGP build. See
 * mobile/PROGRESS.md milestone 3's and milestone 7's notes for exactly
 * what was and wasn't verified.
 *
 * Foreground service (FOREGROUND_SERVICE_LOCATION type, PRD.md ss5.1/ss8)
 * that:
 * - registers for ActivityRecognition IN_VEHICLE enter/exit transitions
 *   (delivered via TripActivityTransitionReceiver's PendingIntent),
 * - feeds those into TripStateMachine to decide whether a trip is
 *   active (grace window so a red light doesn't end a trip),
 * - starts/stops FusedLocationProviderClient updates to match that -
 *   no GPS activity outside an active trip (PRD.md ss7),
 * - on each location update (milestone 7 - the piece milestone 3
 *   deliberately left as just a hook point), fetches nearby stations
 *   and evaluates them through AlertEngine, posting a
 *   FullScreenAlertNotifier alert for anything that clears the bar.
 *
 * **Route-aware fetch strategy - a real, disclosed simplification of
 * PRD.md ss5.3, not the full design:** the web app samples multiple
 * points along the route and queries each one's own radius (see
 * static/app.js's corridor-search loop) to build full corridor
 * coverage. This service instead does a single radius fetch centered
 * on the driver's *current* position (radius padded to
 * corridorWidthKm + half the lead distance, same reasoning as the web
 * app's own fetchRadius padding), then filters through
 * RouteMatcher.stationsAhead. This is simpler and correct for what's
 * actually near the driver right now, but can miss a station that's
 * on-corridor further ahead than this fetch's radius reaches even
 * though it's still within the lead distance - a real gap versus the
 * web app's multi-sample coverage, not one to silently pretend doesn't
 * exist. Extending to multi-sample fetching is a legitimate follow-up,
 * not required for this milestone's "end-to-end wiring" scope.
 *
 * Destination is passed in via ACTION_START_MONITORING's extras
 * (already-geocoded lat/lon) - geocoding the free-text destination
 * itself is MainActivity's job, not this service's (see
 * MainActivity.kt's startTrip()).
 *
 * Assumes ACCESS_FINE_LOCATION/ACCESS_BACKGROUND_LOCATION/
 * ACTIVITY_RECOGNITION are already granted before this service starts -
 * MainActivity's permission flow (milestone 6) is what actually
 * requests them; the checks below are defensive, not that request flow
 * itself.
 */
class TripMonitorService : Service() {

    private lateinit var activityRecognitionClient: ActivityRecognitionClient
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var settings: SettingsRepository
    private lateinit var priceFetcher: PriceFetcher
    private var locationCallback: LocationCallback? = null
    private val tripState = TripStateMachine(GRACE_WINDOW_MILLIS)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Non-null only while a trip is active - see stopLocationUpdatesAndSelf(). */
    private var alertEngine: AlertEngine? = null
    private var destination: LatLon? = null
    private var cachedRoute: RouteResult? = null

    override fun onCreate() {
        super.onCreate()
        activityRecognitionClient = ActivityRecognition.getClient(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        settings = SettingsRepository(this)
        priceFetcher = PriceFetcher(BuildConfig.BACKEND_BASE_URL)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        when (intent?.action) {
            ACTION_START_MONITORING -> {
                val lat = intent.getDoubleExtra(EXTRA_DESTINATION_LAT, Double.NaN)
                val lon = intent.getDoubleExtra(EXTRA_DESTINATION_LON, Double.NaN)
                destination = if (!lat.isNaN() && !lon.isNaN()) LatLon(lat, lon) else null
                cachedRoute = null
                alertEngine = AlertEngine(settings.currentThreshold())
                registerActivityTransitions()
            }
            ACTION_ACTIVITY_TRANSITION -> handleActivityTransition(intent)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        stopLocationUpdates()
        if (hasPermission(android.Manifest.permission.ACTIVITY_RECOGNITION)) {
            activityRecognitionClient.removeActivityTransitionUpdates(activityTransitionPendingIntent(this))
        }
        super.onDestroy()
    }

    private fun registerActivityTransitions() {
        if (!hasPermission(android.Manifest.permission.ACTIVITY_RECOGNITION)) return
        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build(),
        )
        activityRecognitionClient.requestActivityTransitionUpdates(
            ActivityTransitionRequest(transitions),
            activityTransitionPendingIntent(this),
        )
    }

    private fun handleActivityTransition(intent: Intent) {
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val now = System.currentTimeMillis()
        var tripActive = tripState.isTripActive
        for (event in result.transitionEvents) {
            if (event.activityType != DetectedActivity.IN_VEHICLE) continue
            val signal = if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                DrivingSignal.DRIVING
            } else {
                DrivingSignal.NOT_DRIVING
            }
            tripActive = tripState.onSignal(signal, now)
        }
        if (tripActive) startLocationUpdates() else stopLocationUpdatesAndSelf()
    }

    private fun startLocationUpdates() {
        if (locationCallback != null) return // already running
        if (!hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)) return

        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, LOCATION_INTERVAL_MILLIS).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                onLocationUpdate(LatLon(location.latitude, location.longitude))
            }
        }
        locationCallback = callback
        fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    private fun stopLocationUpdatesAndSelf() {
        stopLocationUpdates()
        alertEngine = null
        destination = null
        cachedRoute = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onLocationUpdate(position: LatLon) {
        val engine = alertEngine ?: return
        serviceScope.launch {
            val dest = destination
            val liveCandidates: List<Pair<StationPrice, Double>> = if (dest != null) {
                fetchRouteAwareCandidates(position, dest) ?: return@launch
            } else {
                fetchNearMeCandidates(position) ?: return@launch
            }
            processCandidates(engine, liveCandidates)
        }
    }

    /** Returns null (rather than an empty list) when the fetch itself
      * failed, so the caller can tell "no stations right now" apart
      * from "couldn't check" - AlertEngine.expireStationsNotIn must
      * never be called with an empty set just because a network call
      * failed, or it would wrongly un-mute every station mid-trip. */
    private suspend fun fetchNearMeCandidates(position: LatLon): List<Pair<StationPrice, Double>>? {
        val result = priceFetcher.fetchStations(position, settings.searchRadiusKm, settings.fuelType)
        val stations = (result as? BackendResult.Success)?.value ?: return null
        return stations.map { it to 0.0 } // near-me has no "ahead" concept - PRD.md ss5.3
    }

    private suspend fun fetchRouteAwareCandidates(position: LatLon, destination: LatLon): List<Pair<StationPrice, Double>>? {
        var route = cachedRoute
        if (route == null) {
            val routeResult = priceFetcher.fetchRoute(position, destination, routeType = "fastest")
            route = (routeResult as? BackendResult.Success)?.value ?: return null
            cachedRoute = route
        }

        // Fetch strategy simplification - see class doc.
        val fetchRadius = settings.corridorWidthKm + settings.leadDistanceKm / 2 + 1
        val stationsResult = priceFetcher.fetchStations(position, fetchRadius, settings.fuelType)
        val stations = (stationsResult as? BackendResult.Success)?.value ?: return null

        val byLocation = stations.associateBy { it.location }
        val currentProgressKm = RouteMatcher.projectOntoRoute(position, route.points).progressKm
        val matches = RouteMatcher.stationsAhead(
            currentPosition = position,
            routePoints = route.points,
            stations = stations.map { it.location },
            corridorWidthKm = settings.corridorWidthKm,
            leadDistanceKm = settings.leadDistanceKm,
        )
        return matches.mapNotNull { match ->
            byLocation[match.station]?.let { station -> station to (match.progressKm - currentProgressKm) }
        }
    }

    private fun processCandidates(engine: AlertEngine, candidates: List<Pair<StationPrice, Double>>) {
        val alertCandidates = candidates.map { (station, _) -> AlertCandidate(station.code, station.pricePerLitre) }
        engine.expireStationsNotIn(alertCandidates.map { it.stationCode }.toSet())
        val decisions = engine.evaluate(alertCandidates)

        val byCode = candidates.associateBy { it.first.code }
        for (decision in decisions) {
            val (station, distanceAheadKm) = byCode[decision.stationCode] ?: continue
            FullScreenAlertNotifier.postAlert(
                context = this,
                stationCode = station.code,
                stationName = station.name,
                pricePerLitre = station.pricePerLitre,
                distanceAheadKm = distanceAheadKm,
                stationLocation = station.location,
            )
        }
    }

    private fun hasPermission(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun buildForegroundNotification(): Notification =
        // Notification.Builder(context, channelId) needs API 26+ - always true
        // here since minSdk is 29 (see app/build.gradle.kts's rationale comment).
        Notification.Builder(this, FuelAlertApplication.TRIP_MONITOR_CHANNEL_ID)
            .setContentTitle("Fuel Alert is watching your drive")
            .setContentText("You'll be alerted if a good fuel deal turns up nearby.")
            .setOngoing(true)
            .build()

    companion object {
        const val ACTION_START_MONITORING = "com.newfuel.fuelalert.action.START_MONITORING"
        const val ACTION_ACTIVITY_TRANSITION = "com.newfuel.fuelalert.action.ACTIVITY_TRANSITION"
        const val EXTRA_DESTINATION_LAT = "destination_lat"
        const val EXTRA_DESTINATION_LON = "destination_lon"

        private const val NOTIFICATION_ID = 1001
        private const val GRACE_WINDOW_MILLIS = 5 * 60 * 1000L // PRD.md ss5.1 - avoids flapping at traffic lights
        private const val LOCATION_INTERVAL_MILLIS = 30_000L

        fun activityTransitionPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, TripActivityTransitionReceiver::class.java)
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        }
    }
}
