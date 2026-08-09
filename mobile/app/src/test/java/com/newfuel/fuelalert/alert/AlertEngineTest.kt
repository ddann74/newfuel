package com.newfuel.fuelalert.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEngineTest {

    @Test
    fun `TargetPrice mode alerts a station at or below the target`() {
        val engine = AlertEngine(ValueThreshold.TargetPrice(maxPricePerLitre = 1.70))
        val decisions = engine.evaluate(
            listOf(
                AlertCandidate("A", pricePerLitre = 1.65),
                AlertCandidate("B", pricePerLitre = 1.70),
                AlertCandidate("C", pricePerLitre = 1.75),
            )
        )
        assertEquals(setOf("A", "B"), decisions.map { it.stationCode }.toSet())
    }

    @Test
    fun `PercentBelowAverage mode computes the cutoff from the current candidate set`() {
        // Average of 1.60, 1.70, 1.80 is 1.70. 5% below that is 1.615.
        val engine = AlertEngine(ValueThreshold.PercentBelowAverage(percent = 5.0))
        val decisions = engine.evaluate(
            listOf(
                AlertCandidate("cheap", pricePerLitre = 1.60),
                AlertCandidate("mid", pricePerLitre = 1.70),
                AlertCandidate("expensive", pricePerLitre = 1.80),
            )
        )
        assertEquals(listOf("cheap"), decisions.map { it.stationCode })
    }

    @Test
    fun `PercentBelowAverage with an empty candidate list alerts nothing, not a crash`() {
        val engine = AlertEngine(ValueThreshold.PercentBelowAverage(percent = 5.0))
        assertTrue(engine.evaluate(emptyList()).isEmpty())
    }

    @Test
    fun `a station already alerted is not alerted again while still a live candidate`() {
        val engine = AlertEngine(ValueThreshold.TargetPrice(maxPricePerLitre = 1.70))
        val firstPass = engine.evaluate(listOf(AlertCandidate("A", 1.60)))
        assertEquals(listOf("A"), firstPass.map { it.stationCode })

        // Same station, still clearing the threshold, offered again on the
        // next check cycle (PRD.md ss5.3 re-runs the check periodically) -
        // must not alert a second time.
        val secondPass = engine.evaluate(listOf(AlertCandidate("A", 1.60)))
        assertTrue("should not re-alert a still-live candidate", secondPass.isEmpty())
    }

    @Test
    fun `a station's mute lifts once it's no longer a live candidate, and it can alert again if it reappears`() {
        val engine = AlertEngine(ValueThreshold.TargetPrice(maxPricePerLitre = 1.70))
        engine.evaluate(listOf(AlertCandidate("A", 1.60)))

        // Station A has been passed / fallen out of range - no longer in
        // the live candidate set.
        engine.expireStationsNotIn(currentCandidateCodes = emptySet())

        // If it somehow becomes a candidate again, it's eligible to alert again.
        val reappeared = engine.evaluate(listOf(AlertCandidate("A", 1.60)))
        assertEquals(listOf("A"), reappeared.map { it.stationCode })
    }

    @Test
    fun `expireStationsNotIn leaves still-live stations muted`() {
        val engine = AlertEngine(ValueThreshold.TargetPrice(maxPricePerLitre = 1.70))
        engine.evaluate(listOf(AlertCandidate("A", 1.60), AlertCandidate("B", 1.60)))

        // Only B has been passed - A is still a live candidate.
        engine.expireStationsNotIn(currentCandidateCodes = setOf("A"))

        val result = engine.evaluate(listOf(AlertCandidate("A", 1.60), AlertCandidate("B", 1.60)))
        assertEquals(
            "A should still be muted, B's mute should have lifted",
            listOf("B"),
            result.map { it.stationCode },
        )
    }

    @Test
    fun `reset clears all mute state regardless of expiry`() {
        val engine = AlertEngine(ValueThreshold.TargetPrice(maxPricePerLitre = 1.70))
        engine.evaluate(listOf(AlertCandidate("A", 1.60)))
        engine.reset()

        val result = engine.evaluate(listOf(AlertCandidate("A", 1.60)))
        assertEquals(listOf("A"), result.map { it.stationCode })
    }

    @Test
    fun `a station never clearing the threshold is never alerted`() {
        val engine = AlertEngine(ValueThreshold.TargetPrice(maxPricePerLitre = 1.50))
        val result = engine.evaluate(listOf(AlertCandidate("A", 1.60)))
        assertTrue(result.isEmpty())
    }
}
