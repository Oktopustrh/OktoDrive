package no.oktodrive.app

import org.junit.Assert.*
import org.junit.Test

class RadioDisplayStateTest {
    @Test fun channelNavigationUsesConfirmedPositionAndStopsAtEnds() {
        val channels = listOf(0, 12, 4)
        assertEquals(12, RadioDisplayState.adjacent(channels, 0, 1))
        assertEquals(12, RadioDisplayState.adjacent(channels, 4, -1))
        assertNull(RadioDisplayState.adjacent(channels, 0, -1))
        assertNull(RadioDisplayState.adjacent(channels, 4, 1))
        assertNull(RadioDisplayState.adjacent(channels, null, 1))
        assertNull(RadioDisplayState.adjacent(emptyList(), 0, 1))
    }

    @Test fun simultaneousSpeakersEndIndependently() {
        val state = RadioDisplayState()
        state.voice(7, false, 100)
        state.voice(9, false, 200)
        assertEquals(listOf(7, 9), state.active(300))
        state.voice(7, true, 310)
        assertEquals(listOf(9), state.active(320))
    }

    @Test fun missingTerminatorExpiresButFreshAudioKeepsSpeakerVisible() {
        val state = RadioDisplayState()
        state.voice(7, false, 100)
        state.voice(9, false, 900)
        assertEquals(listOf(9), state.active(1100))
        state.voice(9, false, 1800)
        assertEquals(listOf(9), state.active(1900))
        assertTrue(state.active(2800).isEmpty())
    }

    @Test fun channelChangeAndDisconnectClearSpeakers() {
        val state = RadioDisplayState()
        state.voice(7, false, 100)
        state.clear()
        assertTrue(state.active(101).isEmpty())
    }
}
