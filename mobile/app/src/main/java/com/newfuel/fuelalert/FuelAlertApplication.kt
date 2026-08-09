package com.newfuel.fuelalert

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * Creates the ongoing "trip monitoring active" notification channel
 * TripMonitorService's foreground notification needs (milestone 3) -
 * channel creation is idempotent (recreating with the same ID is a
 * no-op) so doing it unconditionally on every app startup is safe and
 * simpler than tracking whether it's already been created.
 *
 * The full-screen alert's separate, IMPORTANCE_HIGH channel (milestone
 * 5, PROGRESS.md) is not created yet - that's still unbuilt.
 */
class FuelAlertApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                TRIP_MONITOR_CHANNEL_ID,
                "Trip monitoring",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while Fuel Alert is watching a drive for nearby fuel deals."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val TRIP_MONITOR_CHANNEL_ID = "trip_monitor"
    }
}
