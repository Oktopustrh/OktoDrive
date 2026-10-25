package no.oktodrive.app

/** UI-only state: never changes the audio or connection pipeline. */
internal class RadioDisplayState {
    private val speakers = linkedMapOf<Int, Long>()
    fun voice(session: Int, finished: Boolean, now: Long) {
        if (finished) speakers.remove(session) else speakers[session] = now
    }
    fun active(now: Long): List<Int> {
        speakers.entries.removeAll { now - it.value >= 1000 }
        return speakers.keys.toList()
    }
    fun clear() = speakers.clear()

    companion object {
        // Stop at either end; only the server's confirmation updates the display.
        fun adjacent(ids: List<Int>, current: Int?, direction: Int): Int? {
            val index = ids.indexOf(current)
            if (index < 0 || direction !in listOf(-1, 1)) return null
            return ids.getOrNull(index + direction)
        }
    }
}
