package no.oktodrive.app

import org.junit.Assert.*
import org.junit.Test

class TouchLockStateTest {
    @Test fun locksAtTenSecondsAndIgnoresAccidentalTouchWhileLocked() {
        val state = TouchLockState()
        state.unlock(100)
        assertFalse(state.tick(10099, true, true, true))
        assertTrue(state.tick(10100, true, true, true))
        state.touch(10200)
        assertTrue(state.locked)
    }
    @Test fun touchRestartsTimerAndSetupOrMenusSuspendIt() {
        val state = TouchLockState()
        state.unlock(0)
        state.touch(9000)
        assertFalse(state.tick(10000, true, true, true))
        assertFalse(state.tick(19000, true, false, true))
        assertFalse(state.tick(29000, true, true, false))
        assertFalse(state.tick(38999, true, true, true))
        assertTrue(state.tick(39000, true, true, true))
    }
    @Test fun onlyOffThenOnUnlocksAndDisablingAlwaysUnlocks() {
        val state = TouchLockState()
        state.unlock(0)
        state.tick(10000, true, true, true)
        assertFalse(state.screenOn(11000))
        assertTrue(state.locked)
        state.screenOff()
        assertTrue(state.screenOn(12000))
        assertFalse(state.locked)
        assertFalse(state.tick(21999, true, true, true))
        state.tick(22000, true, true, true)
        assertFalse(state.tick(23000, false, true, true))
    }
    @Test fun startupRequiresSetupAndNeverUndoesDeliberateExit() {
        assertTrue(RadioStartupPolicy.shouldConnect(true, "Odd", false))
        assertFalse(RadioStartupPolicy.shouldConnect(false, "Odd", false))
        assertFalse(RadioStartupPolicy.shouldConnect(true, " ", false))
        assertFalse(RadioStartupPolicy.shouldConnect(true, "Odd", true))
        assertTrue(RadioStartupPolicy.canLaunchFromBoot(24, true, true))
        assertFalse(RadioStartupPolicy.canLaunchFromBoot(29, true, true))
        assertFalse(RadioStartupPolicy.canLaunchFromBoot(24, false, true))
    }
}
