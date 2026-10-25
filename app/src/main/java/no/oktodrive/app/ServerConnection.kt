package no.oktodrive.app

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** TM7 may resolve IPv6 first even when that route cannot reach the Mumble port. */
internal object ServerConnection {
    fun connect(
        host: String,
        port: Int,
        resolve: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
        newSocket: () -> Socket = { Socket() }
    ): Socket {
        val addresses = resolve(host).sortedByDescending { it is Inet4Address }
        val failure = IOException("Kunne ikke nå $host på port $port. Nettverksforbindelsen feilet før TLS-kontrollen.")
        for (address in addresses) {
            val socket = newSocket()
            try {
                socket.connect(InetSocketAddress(address, port), 6000)
                return socket
            } catch (e: IOException) {
                failure.addSuppressed(e)
                try { socket.close() } catch (_: IOException) { }
            }
        }
        throw failure
    }
}
