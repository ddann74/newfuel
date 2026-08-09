package com.newfuel.fuelalert.trip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStateMachineTest {

    private val graceWindowMillis = 5 * 60 * 1000L // 5 minutes

    @Test
    fun `trip is inactive before any signal`() {
        val sm = TripStateMachine(graceWindowMillis)
        assertFalse(sm.isTripActive)
    }

    @Test
    fun `trip starts the instant DRIVING is signalled`() {
        val sm = TripStateMachine(graceWindowMillis)
        val active = sm.onSignal(DrivingSignal.DRIVING, atMillis = 0L)
        assertTrue(active)
        assertTrue(sm.isTripActive)
    }

    @Test
    fun `a brief NOT_DRIVING signal within the grace window does not end the trip`() {
        val sm = TripStateMachine(graceWindowMillis)
        sm.onSignal(DrivingSignal.DRIVING, atMillis = 0L)
        // A 30-second stop at a red light - well inside a 5-minute grace window.
        val active = sm.onSignal(DrivingSignal.NOT_DRIVING, atMillis = 30_000L)
        assertTrue("a brief stop should not end the trip", active)
    }

    @Test
    fun `NOT_DRIVING signalled again after the grace window elapses ends the trip`() {
        val sm = TripStateMachine(graceWindowMillis)
        sm.onSignal(DrivingSignal.DRIVING, atMillis = 0L)
        sm.onSignal(DrivingSignal.NOT_DRIVING, atMillis = 30_000L)
        // Grace window is 5 minutes = 300_000ms from the last DRIVING signal.
        val active = sm.onSignal(DrivingSignal.NOT_DRIVING, atMillis = 300_001L)
        assertFalse("trip should end once the grace window has elapsed", active)
    }

    @Test
    fun `a fresh DRIVING signal inside the grace window resets the clock`() {
        val sm = TripStateMachine(graceWindowMillis)
        sm.onSignal(DrivingSignal.DRIVING, atMillis = 0L)
        sm.onSignal(DrivingSignal.NOT_DRIVING, atMillis = 30_000L)
        // Driving resumes at 60s - the grace window should now count from here, not from 0.
        sm.onSignal(DrivingSignal.DRIVING, atMillis = 60_000L)
        val stillActiveAtOldDeadline = sm.onSignal(DrivingSignal.NOT_DRIVING, atMillis = 300_001L)
        assertTrue(
            "grace window should have reset off the second DRIVING signal, not the first",
            stillActiveAtOldDeadline,
        )
    }

    @Test
    fun `checkExpiry ends a trip purely from elapsed time, with no new signal`() {
        val sm = TripStateMachine(graceWindowMillis)
        sm.onSignal(DrivingSignal.DRIVING, atMillis = 0L)
        // No NOT_DRIVING signal ever arrives (e.g. the phone loses activity-recognition
        // updates) - a periodic checkExpiry call must still be able to end the trip.
        val stillActive = sm.checkExpiry(nowMillis = 299_999L)
        assertTrue(stillActive)
        val expired = sm.checkExpiry(nowMillis = 300_001L)
        assertFalse(expired)
    }

    @Test
    fun `checkExpiry is a no-op while no trip is active`() {
        val sm = TripStateMachine(graceWindowMillis)
        assertFalse(sm.checkExpiry(nowMillis = 1_000_000L))
    }
}
