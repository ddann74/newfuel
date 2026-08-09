package com.newfuel.fuelalert.alert

sealed class ValueThreshold {
    data class TargetPrice(val maxPricePerLitre: Double) : ValueThreshold()
    data class PercentBelowAverage(val percent: Double) : ValueThreshold()
}

data class AlertCandidate(val stationCode: String, val pricePerLitre: Double)

data class AlertDecision(val stationCode: String, val pricePerLitre: Double)

/**
 * Decides when a fuel deal is actually worth interrupting the driver
 * for (PRD.md ss5.4) - pure decision logic, no Android dependency, so
 * it's fully unit-testable and fully compile-verifiable, same pattern
 * as RouteMatcher/TripStateMachine.
 *
 * PRD.md ss5.4's debounce rule ("never re-alert for the same station
 * within a trip; an alert for a station expires once the user has
 * passed it or it falls outside the radius") is resolved here as: once
 * a station has triggered an alert, it's muted for as long as it stays
 * a live candidate (so [evaluate] isn't called once per station per
 * check cycle - PRD.md ss5.3 re-runs the corridor/price check
 * periodically, so without this a driver would get spammed every
 * cycle). The mute is lifted - "expires" - the moment a caller reports
 * the station is no longer a candidate at all ([expireStationsNotIn]),
 * matching "passed it" / "fell outside the radius" - not carried
 * forward indefinitely, and not tied to end-of-trip specifically. A
 * genuinely fresh trip should call [reset] rather than relying on
 * expiry alone, since a station a driver never actually passed
 * shouldn't silently be eligible to re-alert just because this trip's
 * bookkeeping was cleared some other way.
 */
class AlertEngine(private val threshold: ValueThreshold) {

    private val alreadyAlerted = mutableSetOf<String>()

    /** Evaluates the current set of live candidates and returns only the
      * ones that both clear the threshold AND haven't already triggered
      * an alert this trip - i.e. exactly what should notify right now. */
    fun evaluate(candidates: List<AlertCandidate>): List<AlertDecision> {
        val cutoff = when (threshold) {
            is ValueThreshold.TargetPrice -> threshold.maxPricePerLitre
            is ValueThreshold.PercentBelowAverage -> {
                if (candidates.isEmpty()) return emptyList()
                val average = candidates.map { it.pricePerLitre }.average()
                average * (1.0 - threshold.percent / 100.0)
            }
        }

        val newAlerts = candidates.filter { it.pricePerLitre <= cutoff && it.stationCode !in alreadyAlerted }
        alreadyAlerted.addAll(newAlerts.map { it.stationCode })
        return newAlerts.map { AlertDecision(it.stationCode, it.pricePerLitre) }
    }

    /** Call with the set of station codes that are still valid
      * candidates right now (still ahead/in-radius) - any previously
      * alerted station NOT in this set has been passed or fallen out of
      * range, and its mute is lifted per the class doc. */
    fun expireStationsNotIn(currentCandidateCodes: Set<String>) {
        alreadyAlerted.retainAll(currentCandidateCodes)
    }

    /** Clears all mute state - call when a trip genuinely ends, not as
      * a substitute for expireStationsNotIn during a trip. */
    fun reset() {
        alreadyAlerted.clear()
    }
}
