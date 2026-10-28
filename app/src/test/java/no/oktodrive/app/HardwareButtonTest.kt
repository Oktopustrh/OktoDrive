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

    @Test fun measuredTm7BindingsUseCodesAndScanCodesNotRepeatCounts() {
        assertTrue(Tm7PlusButtons.bindings.getValue("ptt").matches(230, 230))
        assertFalse(Tm7PlusButtons.bindings.getValue("ptt").matches(201, 230))
        assertTrue(Tm7PlusButtons.bindings.getValue("up").matches(19, 105))
        assertTrue(Tm7PlusButtons.bindings.getValue("down").matches(20, 106))
        assertFalse(Tm7PlusButtons.bindings.getValue("up").matches(19, 106))
    }
    @Test fun longPedalHoldStartsOnceAndReleaseRearms() {
        val state = HardwareButtonState()
        assertTrue(state.press(0))
        for (repeat in 1..201) assertFalse(state.press(repeat))
        assertTrue(state.release())
        assertFalse(state.press(202))
        assertTrue(state.press(0))
        assertTrue(state.release())
    }
    @Test fun presetDetectsOnlyTm7PlusModel() {
        assertTrue(Tm7PlusButtons.isDevice("TM-7Plus"))
        assertTrue(Tm7PlusButtons.isDevice("TM7 Plus"))
        assertFalse(Tm7PlusButtons.isDevice("TM7"))
        assertFalse(Tm7PlusButtons.isDevice("T320"))
    }
}
