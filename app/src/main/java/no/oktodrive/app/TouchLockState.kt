package no.oktodrive.app

/** Touch protection only. It never disables hardware PTT or locks Android credentials. */
internal class TouchLockState(private val delayMs: Long = 10_000L) {
    var locked = false
        private set
    private var lastTouch = 0L
    private var sawScreenOff = false

    fun unlock(now: Long) { locked = false; lastTouch = now; sawScreenOff = false }
    fun touch(now: Long) { if (!locked) lastTouch = now }
    fun tick(now: Long, enabled: Boolean, radioReady: Boolean, focused: Boolean): Boolean {
        if (!enabled) { unlock(now); return false }
        if (!radioReady || !focused) { lastTouch = now; return locked }
        if (now - lastTouch >= delayMs) locked = true
        return locked
    }
    fun screenOff() { sawScreenOff = true }
    fun screenOn(now: Long): Boolean {
        if (!sawScreenOff) return false
        unlock(now)
        return true
    }
}

internal object RadioStartupPolicy {
    fun shouldConnect(setupComplete: Boolean, name: String, exited: Boolean) =
        setupComplete && name.isNotBlank() && !exited
    fun canLaunchFromBoot(sdk: Int, enabled: Boolean, setupComplete: Boolean) =
        sdk < 29 && enabled && setupComplete
}
