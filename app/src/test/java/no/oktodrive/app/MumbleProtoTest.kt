package no.oktodrive.app

import no.oktodrive.app.proto.Authenticate
import no.oktodrive.app.proto.PermissionDenied
import no.oktodrive.app.proto.UserState
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
}
