package com.newfuel.fuelalert.settings

import com.newfuel.fuelalert.alert.ValueThreshold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {

    // Uses SettingsRepository's internal SharedPreferences-accepting
    // constructor (see that class's doc comment) with a real, in-memory
    // fake - not a mocking framework - so this exercises the actual
    // get/set/default logic, not a pre-programmed expectation of it.
    private fun newRepository() = SettingsRepository(FakeSharedPreferences())

    @Test
    fun `defaults match PRD-documented values before anything is set`() {
        val repo = newRepository()
        assertEquals(SettingsRepository.DEFAULT_FUEL_TYPE, repo.fuelType)
        assertEquals(SettingsRepository.DEFAULT_SEARCH_RADIUS_KM.toDouble(), repo.searchRadiusKm, 0.0)
        assertEquals(SettingsRepository.DEFAULT_CORRIDOR_WIDTH_KM.toDouble(), repo.corridorWidthKm, 0.0)
        assertEquals(SettingsRepository.DEFAULT_LEAD_DISTANCE_KM.toDouble(), repo.leadDistanceKm, 0.0)
        assertEquals(ThresholdMode.TARGET_PRICE, repo.thresholdMode)
        assertEquals(SettingsRepository.DEFAULT_TARGET_PRICE.toDouble(), repo.targetPricePerLitre, 0.0)
        assertEquals(SettingsRepository.DEFAULT_PERCENT_BELOW_AVERAGE.toDouble(), repo.percentBelowAverage, 0.0)
        assertTrue(repo.tripAutoStart)
    }

    @Test
    fun `every setting round-trips through the same repository instance`() {
        val repo = newRepository()
        repo.fuelType = "Diesel"
        repo.searchRadiusKm = 25.0
        repo.corridorWidthKm = 8.0
        repo.leadDistanceKm = 15.0
        repo.thresholdMode = ThresholdMode.PERCENT_BELOW_AVERAGE
        repo.targetPricePerLitre = 1.55
        repo.percentBelowAverage = 7.5
        repo.tripAutoStart = false

        assertEquals("Diesel", repo.fuelType)
        assertEquals(25.0, repo.searchRadiusKm, 0.0)
        assertEquals(8.0, repo.corridorWidthKm, 0.0)
        assertEquals(15.0, repo.leadDistanceKm, 0.0)
        assertEquals(ThresholdMode.PERCENT_BELOW_AVERAGE, repo.thresholdMode)
        assertEquals(1.55, repo.targetPricePerLitre, 0.001)
        assertEquals(7.5, repo.percentBelowAverage, 0.001)
        assertEquals(false, repo.tripAutoStart)
    }

    @Test
    fun `settings persist across separate repository instances over the same backing store`() {
        val backingStore = FakeSharedPreferences()
        SettingsRepository(backingStore).fuelType = "U98"

        // A fresh SettingsRepository over the same store - simulates the
        // app process restarting - should see the persisted value, not
        // a default.
        assertEquals("U98", SettingsRepository(backingStore).fuelType)
    }

    @Test
    fun `currentThreshold builds TargetPrice when that mode is active`() {
        val repo = newRepository()
        repo.thresholdMode = ThresholdMode.TARGET_PRICE
        repo.targetPricePerLitre = 1.65

        val threshold = repo.currentThreshold()
        assertTrue(threshold is ValueThreshold.TargetPrice)
        assertEquals(1.65, (threshold as ValueThreshold.TargetPrice).maxPricePerLitre, 0.001)
    }

    @Test
    fun `currentThreshold builds PercentBelowAverage when that mode is active`() {
        val repo = newRepository()
        repo.thresholdMode = ThresholdMode.PERCENT_BELOW_AVERAGE
        repo.percentBelowAverage = 10.0

        val threshold = repo.currentThreshold()
        assertTrue(threshold is ValueThreshold.PercentBelowAverage)
        assertEquals(10.0, (threshold as ValueThreshold.PercentBelowAverage).percent, 0.001)
    }

    @Test
    fun `an unrecognized stored threshold mode falls back to the default rather than crashing`() {
        val backingStore = FakeSharedPreferences()
        // Simulates a value from a future/incompatible app version -
        // SettingsRepository must not crash reading it.
        backingStore.edit().putString("threshold_mode", "SOME_FUTURE_MODE").apply()

        assertEquals(SettingsRepository.DEFAULT_THRESHOLD_MODE, SettingsRepository(backingStore).thresholdMode)
    }
}
