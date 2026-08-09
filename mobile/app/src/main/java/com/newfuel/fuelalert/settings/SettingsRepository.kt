package com.newfuel.fuelalert.settings

import android.content.Context
import android.content.SharedPreferences
import com.newfuel.fuelalert.alert.ValueThreshold

enum class ThresholdMode { TARGET_PRICE, PERCENT_BELOW_AVERAGE }

/**
 * Persists every setting PRD.md ss5.6 lists: fuel type, near-me search
 * radius, route-aware corridor width + lead distance, value threshold
 * mode/value, and trip auto-start behavior. Search *mode* itself
 * (near-me vs. route-aware) isn't stored here - PRD.md ss5.3 derives it
 * from whether a destination is currently set for the trip, not a
 * separate persisted toggle; both radius and corridor-width/lead-
 * distance are kept regardless, since either could be the active one
 * depending on that per-trip state.
 *
 * Backed by plain SharedPreferences (both `Context` and
 * `SharedPreferences` are core Android APIs, not androidx/Play
 * Services), so unlike most of this app's UI-facing code, this file is
 * fully compile-verifiable - see SettingsRepositoryTest.kt for it being
 * fully run-verifiable too, via a fake SharedPreferences rather than a
 * mocking framework. The `internal` SharedPreferences-accepting
 * constructor exists specifically so tests can inject a fake without
 * needing a full fake `Context` (a much larger surface to fake) - the
 * public `Context`-accepting constructor is what real callers use.
 */
class SettingsRepository internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    var fuelType: String
        get() = prefs.getString(KEY_FUEL_TYPE, DEFAULT_FUEL_TYPE) ?: DEFAULT_FUEL_TYPE
        set(value) = prefs.edit().putString(KEY_FUEL_TYPE, value).apply()

    var searchRadiusKm: Double
        get() = prefs.getFloat(KEY_SEARCH_RADIUS_KM, DEFAULT_SEARCH_RADIUS_KM).toDouble()
        set(value) = prefs.edit().putFloat(KEY_SEARCH_RADIUS_KM, value.toFloat()).apply()

    var corridorWidthKm: Double
        get() = prefs.getFloat(KEY_CORRIDOR_WIDTH_KM, DEFAULT_CORRIDOR_WIDTH_KM).toDouble()
        set(value) = prefs.edit().putFloat(KEY_CORRIDOR_WIDTH_KM, value.toFloat()).apply()

    var leadDistanceKm: Double
        get() = prefs.getFloat(KEY_LEAD_DISTANCE_KM, DEFAULT_LEAD_DISTANCE_KM).toDouble()
        set(value) = prefs.edit().putFloat(KEY_LEAD_DISTANCE_KM, value.toFloat()).apply()

    var thresholdMode: ThresholdMode
        get() {
            val name = prefs.getString(KEY_THRESHOLD_MODE, null) ?: return DEFAULT_THRESHOLD_MODE
            return runCatching { ThresholdMode.valueOf(name) }.getOrDefault(DEFAULT_THRESHOLD_MODE)
        }
        set(value) = prefs.edit().putString(KEY_THRESHOLD_MODE, value.name).apply()

    var targetPricePerLitre: Double
        get() = prefs.getFloat(KEY_TARGET_PRICE, DEFAULT_TARGET_PRICE).toDouble()
        set(value) = prefs.edit().putFloat(KEY_TARGET_PRICE, value.toFloat()).apply()

    var percentBelowAverage: Double
        get() = prefs.getFloat(KEY_PERCENT_BELOW_AVERAGE, DEFAULT_PERCENT_BELOW_AVERAGE).toDouble()
        set(value) = prefs.edit().putFloat(KEY_PERCENT_BELOW_AVERAGE, value.toFloat()).apply()

    var tripAutoStart: Boolean
        get() = prefs.getBoolean(KEY_TRIP_AUTO_START, DEFAULT_TRIP_AUTO_START)
        set(value) = prefs.edit().putBoolean(KEY_TRIP_AUTO_START, value).apply()

    /** The single ValueThreshold AlertEngine actually needs, built from
      * whichever mode/value pair is currently active - callers never
      * need to branch on [thresholdMode] themselves. */
    fun currentThreshold(): ValueThreshold = when (thresholdMode) {
        ThresholdMode.TARGET_PRICE -> ValueThreshold.TargetPrice(targetPricePerLitre)
        ThresholdMode.PERCENT_BELOW_AVERAGE -> ValueThreshold.PercentBelowAverage(percentBelowAverage)
    }

    companion object {
        private const val PREFS_NAME = "fuel_alert_settings"

        private const val KEY_FUEL_TYPE = "fuel_type"
        private const val KEY_SEARCH_RADIUS_KM = "search_radius_km"
        private const val KEY_CORRIDOR_WIDTH_KM = "corridor_width_km"
        private const val KEY_LEAD_DISTANCE_KM = "lead_distance_km"
        private const val KEY_THRESHOLD_MODE = "threshold_mode"
        private const val KEY_TARGET_PRICE = "target_price_per_litre"
        private const val KEY_PERCENT_BELOW_AVERAGE = "percent_below_average"
        private const val KEY_TRIP_AUTO_START = "trip_auto_start"

        const val DEFAULT_FUEL_TYPE = "E10"
        const val DEFAULT_SEARCH_RADIUS_KM = 10f
        const val DEFAULT_CORRIDOR_WIDTH_KM = 5f
        const val DEFAULT_LEAD_DISTANCE_KM = 10f
        val DEFAULT_THRESHOLD_MODE = ThresholdMode.TARGET_PRICE
        const val DEFAULT_TARGET_PRICE = 1.70f
        const val DEFAULT_PERCENT_BELOW_AVERAGE = 5f
        const val DEFAULT_TRIP_AUTO_START = true

        /** Every fuel type NSW FuelCheck reports, matching the web
          * app's <select id="fueltype"> options exactly (static/app.js). */
        val FUEL_TYPES = listOf("E10", "U91", "U95", "U98", "P98", "Diesel")
    }
}
