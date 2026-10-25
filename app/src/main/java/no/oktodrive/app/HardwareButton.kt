package no.oktodrive.app

/** Explicitly learned bindings only; never guess vendor-specific TM7 key codes. */
internal data class HardwareButton(val keyCode: Int, val scanCode: Int) {
    fun matches(code: Int, scan: Int) = keyCode == code && scanCode == scan
}

internal class HardwareButtonState {
    private var held = false
    fun press(repeat: Int): Boolean {
        if (held || repeat != 0) return false
        held = true
        return true
    }
    fun release(): Boolean {
        val wasHeld = held
        held = false
        return wasHeld
    }
}
