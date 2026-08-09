package com.newfuel.fuelalert

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.os.Build

/**
 * Creates both notification channels this app needs - channel creation
 * is idempotent (recreating with the same ID is a no-op) so doing it
 * unconditionally on every app startup is safe and simpler than
 * tracking whether it's already been created.
 *
 * - "trip_monitor": the ongoing, low-importance "watching your drive"
 *   notification TripMonitorService's foreground service needs
 *   (milestone 3).
 * - "fuel_alert": the full-screen deal alert (milestone 5, PRD.md
 *   ss5.5) - IMPORTANCE_HIGH with an alarm-category sound, since this
 *   is meant to be as unmissable as an alarm, not a routine ping.
 */
class FuelAlertApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(
                NotificationChannel(
                    TRIP_MONITOR_CHANNEL_ID,
                    "Trip monitoring",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shown while Fuel Alert is watching a drive for nearby fuel deals."
                }
            )

            manager.createNotificationChannel(
                NotificationChannel(
                    FUEL_ALERT_CHANNEL_ID,
                    "Fuel deal alerts",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "A full-screen alert when a fuel deal worth stopping for is nearby."
                    setBypassDnd(true)
                    setSound(
                        android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                }
            )
        }
    }

    companion object {
        const val TRIP_MONITOR_CHANNEL_ID = "trip_monitor"
        const val FUEL_ALERT_CHANNEL_ID = "fuel_alert"
    }
}
