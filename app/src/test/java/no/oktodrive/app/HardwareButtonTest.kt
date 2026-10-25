package no.oktodrive.app

import org.junit.Assert.*
import org.junit.Test

class HardwareButtonTest {
    @Test fun repeatedDownDoesNotRetriggerAndReleaseRearms() {
        val state = HardwareButtonState()
        assertTrue(state.press(0))
        assertFalse(state.press(1))
        assertFalse(state.press(0))
        assertTrue(state.release())
        assertFalse(state.release())
        assertFalse(state.press(4)) // focus restored mid-hold must not transmit
        assertTrue(state.press(0))
    }
    @Test fun differentScanCodeDoesNotActivateBinding() {
        val button = HardwareButton(0, 224)
        assertTrue(button.matches(0, 224))
        assertFalse(button.matches(0, 225))
        assertFalse(button.matches(1, 224))
    }
}
