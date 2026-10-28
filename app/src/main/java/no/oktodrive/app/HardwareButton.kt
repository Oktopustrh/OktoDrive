package no.oktodrive.app

/** A binding uses both the Android key code and the measured device scan code. */
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

/** Measured on Inrico TM-7Plus, Android 7/API 24. repeatCount is never a key code. */
internal object Tm7PlusButtons {
    val bindings = mapOf(
        "ptt" to HardwareButton(230, 230),
        "up" to HardwareButton(19, 105),
        "down" to HardwareButton(20, 106)
    )

    fun isDevice(model: String): Boolean =
        model.replace("-", "").replace("_", "").replace(" ", "")
            .equals("TM7Plus", ignoreCase = true)
}
