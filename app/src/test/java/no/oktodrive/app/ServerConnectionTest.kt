package no.oktodrive.app

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import org.junit.Assert.*
import org.junit.Test

class ServerConnectionTest {
    private val ipv4 = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val ipv6 = InetAddress.getByAddress(ByteArray(16).apply { this[15] = 1 })
    private class ProbeSocket(val fail: Boolean) : Socket() {
        var target: InetSocketAddress? = null
        var wasClosed = false
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            target = endpoint as InetSocketAddress
            assertEquals(6000, timeout)
            if (fail) throw IOException("Unreachable test route")
        }
        override fun close() { wasClosed = true }
    }

    @Test fun ipv6FirstDnsStillConnectsIpv4First() {
        val socket = ProbeSocket(false)
        val result = ServerConnection.connect("radio.example", 64738,
            { arrayOf(ipv6, ipv4) }, { socket })
        assertSame(socket, result)
        assertEquals(ipv4, socket.target!!.address)
        assertEquals(64738, socket.target!!.port)
        assertFalse(socket.wasClosed)
    }

    @Test fun failedIpv4ClosesAndFallsBackToIpv6() {
        val sockets = listOf(ProbeSocket(true), ProbeSocket(false))
        var index = 0
        val result = ServerConnection.connect("radio.example", 64738,
            { arrayOf(ipv6, ipv4) }, { sockets[index++] })
        assertSame(sockets[1], result)
        assertTrue(sockets[0].wasClosed)
        assertFalse(sockets[1].wasClosed)
        assertEquals(ipv4, sockets[0].target!!.address)
        assertEquals(ipv6, sockets[1].target!!.address)
    }

    @Test fun ipv6OnlyRemainsSupported() {
        val socket = ProbeSocket(false)
        ServerConnection.connect("radio.example", 64738, { arrayOf(ipv6) }, { socket })
        assertEquals(ipv6, socket.target!!.address)
    }

    @Test fun allFailuresCloseSocketsAndReportNetworkBeforeTls() {
        val sockets = listOf(ProbeSocket(true), ProbeSocket(true))
        var index = 0
        try {
            ServerConnection.connect("radio.example", 64738,
                { arrayOf(ipv6, ipv4) }, { sockets[index++] })
            fail("Must reject unreachable server")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("før TLS"))
            assertEquals(2, e.suppressed.size)
            assertTrue(sockets.all { it.wasClosed })
        }
    }
}
