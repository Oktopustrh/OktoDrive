package no.oktodrive.app

import com.google.protobuf.ByteString
import no.oktodrive.app.proto.Authenticate
import no.oktodrive.app.proto.PermissionDenied
import no.oktodrive.app.proto.UserState
import no.oktodrive.app.proto.udp.Audio
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MumbleProtoTest {
    @Test
    fun usernameWithSpacesRoundTripsExactly() {
        val original = "Odd Skaset"
        val encoded = Authenticate.newBuilder()
            .setUsername(original)
            .setOpus(true)
            .build()
            .toByteArray()

        val decoded = Authenticate.parseFrom(encoded)

        assertEquals(original, decoded.username)
        assertTrue(decoded.opus)
    }

    @Test
    fun channelSwitchCarriesSessionAndChannelId() {
        val encoded = UserState.newBuilder()
            .setSession(42)
            .setChannelId(17)
            .build()
            .toByteArray()

        val decoded = UserState.parseFrom(encoded)

        assertTrue(decoded.hasSession())
        assertTrue(decoded.hasChannelId())
        assertEquals(42, decoded.session)
        assertEquals(17, decoded.channelId)
    }

    @Test
    fun permissionDeniedPreservesServerDetails() {
        val encoded = PermissionDenied.newBuilder()
            .setType(PermissionDenied.DenyType.Permission)
            .setChannelId(17)
            .setSession(42)
            .setReason("denied")
            .build()
            .toByteArray()

        val decoded = PermissionDenied.parseFrom(encoded)

        assertEquals(PermissionDenied.DenyType.Permission, decoded.type)
        assertEquals(17, decoded.channelId)
        assertEquals(42, decoded.session)
        assertEquals("denied", decoded.reason)
    }

    @Test
    fun udpAudioCarriesTargetFrameAndOpusPayload() {
        val opus = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = Audio.newBuilder()
            .setTarget(0)
            .setFrameNumber(123)
            .setOpusData(ByteString.copyFrom(opus))
            .build()
            .toByteArray()

        val decoded = Audio.parseFrom(encoded)

        assertTrue(decoded.hasTarget())
        assertEquals(0, decoded.target)
        assertEquals(123L, decoded.frameNumber)
        assertArrayEquals(opus, decoded.opusData.toByteArray())
    }
}
