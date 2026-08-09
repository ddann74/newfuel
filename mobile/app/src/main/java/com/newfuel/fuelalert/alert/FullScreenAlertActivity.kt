package com.newfuel.fuelalert.alert

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.newfuel.fuelalert.R

/**
 * The actual full-screen deal alert (PRD.md ss5.5) - launched either
 * directly via the notification's full-screen intent (screen off/locked)
 * or by tapping the fallback heads-up notification (screen already on -
 * see FullScreenAlertNotifier.kt's doc comment on why the full-screen
 * intent is posted unconditionally either way).
 *
 * Plain `Activity`, not `AppCompatActivity`, and `findViewById` rather
 * than generated ViewBinding - both deliberate, to keep this file's
 * only unverifiable-in-this-sandbox dependency down to `R` itself
 * (unavoidable without a real Gradle build, same as MainActivity.kt),
 * not also androidx.appcompat or a generated Binding class on top of it.
 *
 * `setShowWhenLocked`/`setTurnScreenOn` (API 27+, always available -
 * minSdk is 29) show this activity over a locked screen WITHOUT
 * dismissing the keyguard - matches alarm-clock-style behavior: visible
 * immediately, but doesn't bypass the user's own lock security.
 */
class FullScreenAlertActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(R.layout.activity_full_screen_alert)

        val stationCode = intent.getStringExtra(EXTRA_STATION_CODE) ?: run { finish(); return }
        val stationName = intent.getStringExtra(EXTRA_STATION_NAME).orEmpty()
        val pricePerLitre = intent.getDoubleExtra(EXTRA_PRICE_PER_LITRE, 0.0)
        val distanceAheadKm = intent.getDoubleExtra(EXTRA_DISTANCE_AHEAD_KM, 0.0)
        val stationLat = intent.getDoubleExtra(EXTRA_STATION_LAT, 0.0)
        val stationLon = intent.getDoubleExtra(EXTRA_STATION_LON, 0.0)

        findViewById<TextView>(R.id.stationNameText).text = stationName
        findViewById<TextView>(R.id.priceText).text = "$%.3f/L".format(pricePerLitre)
        findViewById<TextView>(R.id.distanceText).text = "%.1f km ahead".format(distanceAheadKm)

        findViewById<Button>(R.id.navigateButton).setOnClickListener {
            // Navigation-only Waze integration (PRD.md ss5.3's "Resolved
            // 2026-08-09" note) - a deep link, same mechanism the web
            // app's nav-link already uses, never a data source.
            val wazeUri = Uri.parse("https://waze.com/ul?ll=$stationLat,$stationLon&navigate=yes")
            startActivity(Intent(Intent.ACTION_VIEW, wazeUri))
            FullScreenAlertNotifier.dismiss(this, stationCode)
            finish()
        }

        findViewById<Button>(R.id.snoozeButton).setOnClickListener {
            // Snoozing just closes this alert without re-muting the
            // station in AlertEngine (milestone 4) - it's still a live
            // candidate, so it may alert again on a later check cycle.
            // Wiring that decision into TripMonitorService is milestone
            // 7's job (end-to-end wiring), not this activity's.
            FullScreenAlertNotifier.dismiss(this, stationCode)
            finish()
        }

        findViewById<Button>(R.id.dismissButton).setOnClickListener {
            FullScreenAlertNotifier.dismiss(this, stationCode)
            finish()
        }
    }

    companion object {
        const val EXTRA_STATION_CODE = "station_code"
        const val EXTRA_STATION_NAME = "station_name"
        const val EXTRA_PRICE_PER_LITRE = "price_per_litre"
        const val EXTRA_DISTANCE_AHEAD_KM = "distance_ahead_km"
        const val EXTRA_STATION_LAT = "station_lat"
        const val EXTRA_STATION_LON = "station_lon"
    }
}
