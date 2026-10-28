package no.oktodrive.app

/** A stopped capture must discard even a partial frame or a pending read error. */
internal object PcmFrameReader {
    fun fill(pcm: ShortArray, running: () -> Boolean, read: (ShortArray, Int, Int) -> Int): Boolean {
        var filled = 0
        while (running() && filled < pcm.size) {
            val count = read(pcm, filled, pcm.size - filled)
            // stop() can unblock an in-flight AudioRecord.read with a negative result.
            if (!running()) return false
            check(count >= 0) { "AudioRecord.read feilet under sending: $count" }
            check(count <= pcm.size - filled) { "Ugyldig mikrofonramme: $count" }
            filled += count
        }
        return running() && filled == pcm.size
    }
}
