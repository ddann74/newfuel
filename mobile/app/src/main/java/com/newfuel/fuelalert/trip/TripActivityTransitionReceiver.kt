package com.newfuel.fuelalert.trip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.ActivityTransitionResult

/**
 * UNVERIFIED - see TripMonitorService.kt's doc comment: this file also
 * depends on Play Services (ActivityTransitionResult), which is not
 * reachable in this sandbox.
 *
 * requestActivityTransitionUpdates() delivers results via a
 * PendingIntent broadcast, not a direct in-process callback - this
 * receiver's only job is to recognize a real transition result and
 * hand it straight to TripMonitorService, doing no other work itself
 * (BroadcastReceiver.onReceive has a short execution-time budget, so
 * anything more than "forward it on" belongs in the service, not here).
 */
class TripActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return

        val forward = Intent(context, TripMonitorService::class.java).apply {
            action = TripMonitorService.ACTION_ACTIVITY_TRANSITION
            putExtras(intent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(forward)
        } else {
            context.startService(forward)
        }
    }
}
