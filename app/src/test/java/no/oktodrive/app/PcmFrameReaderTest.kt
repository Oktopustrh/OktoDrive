package no.oktodrive.app

import org.junit.Assert.*
import org.junit.Test

class PcmFrameReaderTest {
    @Test fun releaseDuringReadDiscardsError() {
        var running = true
        assertFalse(PcmFrameReader.fill(ShortArray(960), { running }) { _, _, _ ->
            running = false
            -1
        })
    }

    @Test fun errorWhileHeldIsStillReported() {
        try {
            PcmFrameReader.fill(ShortArray(960), { true }) { _, _, _ -> -1 }
            fail("A real capture failure must not be hidden")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("-1"))
        }
    }

    @Test fun partialReadsFillOneCompleteFrame() {
        val offsets = mutableListOf<Int>()
        assertTrue(PcmFrameReader.fill(ShortArray(960), { true }) { _, offset, size ->
            offsets.add(offset)
            minOf(240, size)
        })
        assertEquals(listOf(0, 240, 480, 720), offsets)
    }

    @Test fun releaseDiscardsPartialFrame() {
        var running = true
        var reads = 0
        assertFalse(PcmFrameReader.fill(ShortArray(960), { running }) { _, _, _ ->
            reads++
            if (reads == 2) running = false
            240
        })
        assertEquals(2, reads)
    }

    @Test fun alreadyStoppedDoesNotReadMicrophone() {
        assertFalse(PcmFrameReader.fill(ShortArray(960), { false }) { _, _, _ ->
            throw AssertionError("Capture is stopped")
        })
    }
}
