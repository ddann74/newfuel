package com.newfuel.fuelalert.trip

enum class DrivingSignal { DRIVING, NOT_DRIVING }

/**
 * Decides whether a trip is active from a stream of driving/not-driving
 * signals (ActivityRecognition's IN_VEHICLE enter/exit transitions,
 * PRD.md ss5.1) - deliberately has zero Android/Play-Services
 * dependency so it's fully unit-testable and fully compile-verifiable
 * in a sandbox that can't reach Play Services artifacts at all (see
 * TripMonitorService.kt's doc comment for why that file, unlike this
 * one, could not be compiled here).
 *
 * A trip starts the instant DRIVING is signalled. It does NOT end the
 * instant NOT_DRIVING is signalled - PRD.md ss5.1 explicitly calls for
 * a grace window "to avoid flapping at traffic lights": a trip only
 * really ends once [graceWindowMillis] has elapsed with no further
 * DRIVING signal. [checkExpiry] lets a caller re-evaluate that purely
 * from the passage of time (e.g. on a periodic timer), without needing
 * a fresh signal to trigger the check - a real red light can easily
 * outlast the grace window with no new signal arriving at all.
 */
class TripStateMachine(private val graceWindowMillis: Long) {

    private var lastDrivingAtMillis: Long? = null
    private var tripActive = false

    val isTripActive: Boolean
        get() = tripActive

    /** Feed a new signal at [atMillis]. Returns the trip-active state
      * after processing it. */
    fun onSignal(signal: DrivingSignal, atMillis: Long): Boolean {
        when (signal) {
            DrivingSignal.DRIVING -> {
                lastDrivingAtMillis = atMillis
                tripActive = true
            }
            DrivingSignal.NOT_DRIVING -> {
                expireIfPastGraceWindow(atMillis)
            }
        }
        return tripActive
    }

    /** Re-checks whether the grace window has elapsed as of [nowMillis],
      * with no new signal - the case a purely signal-driven state
      * machine would miss (see class doc). Returns the trip-active state
      * after the check. */
    fun checkExpiry(nowMillis: Long): Boolean {
        expireIfPastGraceWindow(nowMillis)
        return tripActive
    }

    private fun expireIfPastGraceWindow(atMillis: Long) {
        val last = lastDrivingAtMillis
        if (tripActive && last != null && atMillis - last >= graceWindowMillis) {
            tripActive = false
        }
    }
}
