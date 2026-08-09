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
import com.newfuel.fuelalert.FuelAlertApplication
import com.newfuel.fuelalert.route.LatLon

/**
 * UNVERIFIED BEYOND CAREFUL REVIEW - this file could NOT be compiled in
 * this sandbox, confirmed by actually trying: com.google.android.gms.*
 * (Play Services) is published only on Google's Maven repo
 * (maven.google.com / dl.google.com), which this sandbox cannot reach -
 * a 404 from Maven Central for play-services-location confirmed this
 * before writing a line of this file, the same standard applied to
 * every other Google-Maven-only dependency this session (androidx.*,
 * media3 in ddann74/spot_block). The trip start/end DECISION logic
 * itself lives in TripStateMachine.kt instead, specifically so it could
 * be pulled out of this unverifiable file and be genuinely
 * compiled-and-run-tested (see TripStateMachineTest.kt) - this file is
 * deliberately kept as thin as possible around that verified core, but
 * "thin" isn't "zero risk"; open this in Android Studio and let Gradle
 * resolve the real Play Services APIs before trusting it compiles.
 *
 * Foreground service (FOREGROUND_SERVICE_LOCATION type, PRD.md ss5.1/ss8)
 * that:
 * - registers for ActivityRecognition IN_VEHICLE enter/exit transitions
 *   (delivered via TripActivityTransitionReceiver, since
 *   requestActivityTransitionUpdates delivers results through a
 *   PendingIntent, not a direct in-process callback),
 * - feeds those transitions into TripStateMachine to decide whether a
 *   trip is active (grace window included, so a red light doesn't end
 *   a trip - see TripStateMachine's doc comment),
 * - starts/stops FusedLocationProviderClient location updates to match
 *   that decision - no GPS activity outside an active trip (PRD.md ss7).
 *
 * Does NOT yet call into RouteMatcher/PriceFetcher/AlertEngine on each
 * location update - AlertEngine (milestone 4) doesn't exist yet, so
 * onLocationUpdate() is deliberately just the hook point for that, not
 * a stub pretending to do more than it does.
 *
 * Assumes ACCESS_FINE_LOCATION/ACCESS_BACKGROUND_LOCATION/
 * ACTIVITY_RECOGNITION are already granted before this service starts -
 * the actual permission-request UI (with an explainer before each
 * system prompt, per PRD.md ss7) is a Settings-screen concern
 * (milestone 6), not yet built. The permission checks below are
 * defensive (avoid a SecurityException crash), not a substitute for
 * that real request flow.
 */
class TripMonitorService : Service() {

    private lateinit var activityRecognitionClient: ActivityRecognitionClient
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private val tripState = TripStateMachine(GRACE_WINDOW_MILLIS)

    override fun onCreate() {
        super.onCreate()
        activityRecognitionClient = ActivityRecognition.getClient(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        when (intent?.action) {
            ACTION_START_MONITORING -> registerActivityTransitions()
            ACTION_ACTIVITY_TRANSITION -> handleActivityTransition(intent)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
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
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Hook point for milestone 4 (AlertEngine) - not built yet, so
      * deliberately does nothing beyond receiving the update for now. */
    private fun onLocationUpdate(position: LatLon) {
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
