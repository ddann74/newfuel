package com.newfuel.fuelalert.alert

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.newfuel.fuelalert.FuelAlertApplication
import com.newfuel.fuelalert.R
import com.newfuel.fuelalert.route.LatLon

/**
 * Builds and posts the actual full-screen deal alert (PRD.md ss5.5).
 *
 * `setFullScreenIntent(pendingIntent, /* highPriority = */ true)` is
 * used unconditionally, regardless of [FullScreenIntentPermission] -
 * that's deliberate, not an oversight: per the confirmed real behavior
 * documented in FullScreenIntentPermission.kt's doc comment, a
 * full-screen intent without the permission granted still degrades
 * gracefully to a heads-up notification (capped at ~60s) rather than
 * being dropped, so there's no reason to skip calling it. Whether to
 * proactively send the user to grant the permission first (via
 * FullScreenIntentPermission.settingsIntent) is a first-run/Settings-
 * screen UX decision, not this class's job - this class only ever
 * builds and posts the notification for whatever grant state
 * currently holds.
 */
object FullScreenAlertNotifier {

    fun postAlert(
        context: Context,
        stationCode: String,
        stationName: String,
        pricePerLitre: Double,
        distanceAheadKm: Double,
        stationLocation: LatLon,
    ) {
        val fullScreenIntent = Intent(context, FullScreenAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(FullScreenAlertActivity.EXTRA_STATION_CODE, stationCode)
            putExtra(FullScreenAlertActivity.EXTRA_STATION_NAME, stationName)
            putExtra(FullScreenAlertActivity.EXTRA_PRICE_PER_LITRE, pricePerLitre)
            putExtra(FullScreenAlertActivity.EXTRA_DISTANCE_AHEAD_KM, distanceAheadKm)
            putExtra(FullScreenAlertActivity.EXTRA_STATION_LAT, stationLocation.lat)
            putExtra(FullScreenAlertActivity.EXTRA_STATION_LON, stationLocation.lon)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            stationCode.hashCode(), // distinct request code per station, so alerts for different stations don't overwrite each other's PendingIntent
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val notification = Notification.Builder(context, FuelAlertApplication.FUEL_ALERT_CHANNEL_ID)
            .setContentTitle("Fuel deal ahead: $stationName")
            .setContentText("$%.3f/L, %.1f km ahead".format(pricePerLitre, distanceAheadKm))
            // A dedicated monochrome notification icon (status-bar icons should
            // be a simple silhouette, not the full-color launcher icon) is a
            // real follow-up, not required for this to function - reusing
            // ic_launcher here rather than leaving a required Builder call unset.
            .setSmallIcon(R.drawable.ic_launcher)
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(stationCode.hashCode(), notification)
    }

    fun dismiss(context: Context, stationCode: String) {
        context.getSystemService(NotificationManager::class.java).cancel(stationCode.hashCode())
    }
}
