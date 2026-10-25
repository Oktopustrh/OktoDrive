package no.oktodrive.app

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Certificate discovery and certificate pinning for Mumble TLS.
 *
 * The discovery handshake intentionally accepts an untrusted certificate only
 * to read its public fingerprint. No application data, usernames, passwords or
 * Mumble messages are transmitted during discovery. The user must verify the
 * fingerprint using an independent trusted channel before pinning.
 *
 * Every subsequent handshake validates the pinned leaf certificate. A changed
 * certificate is rejected, not silently accepted.
 */
internal object ServerCertificate {
    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it.toInt() and 0xff) }

    /**
     * Android may prefer an AAAA record even when the Mumble service only
     * listens on IPv4. Resolve all addresses explicitly and try IPv4 first,
     * then fall back to IPv6 if needed.
     */
    private fun connectPreferred(
        context: SSLContext,
        host: String,
        port: Int,
        timeoutMs: Int
    ): SSLSocket {
        val addresses = InetAddress.getAllByName(host)
            .sortedByDescending { it is Inet4Address }

        if (addresses.isEmpty()) {
            throw IOException("Fant ingen IP-adresse for $host")
        }

        var lastError: Exception? = null
        for (address in addresses) {
            val socket = context.socketFactory.createSocket() as SSLSocket
            try {
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(address, port), timeoutMs)
                return socket
            } catch (e: Exception) {
                lastError = e
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }

        throw lastError ?: IOException("Kunne ikke koble til $host:$port")
    }

    private fun handshake(
        host: String,
        port: Int,
        trustManager: X509TrustManager
    ): X509Certificate {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<javax.net.ssl.TrustManager>(trustManager), null)
        val socket = connectPreferred(context, host, port, 6000)
        socket.use {
            it.startHandshake()
            return it.session.peerCertificates.first() as X509Certificate
        }
    }

    fun discover(host: String, port: Int): X509Certificate {
        val manager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("Client certificates are not supported")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("Server sent no certificate")
                chain.first().checkValidity()
            }
        }
        return handshake(host, port, manager)
    }

    fun verifyPinned(host: String, port: Int, expectedFingerprint: String) {
        val manager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("Client certificates are not supported")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("Server sent no certificate")
                chain.first().checkValidity()
                if (fingerprint(chain.first()) != expectedFingerprint) {
                    throw CertificateException("Server certificate has changed (fingerprint mismatch)")
                }
            }
        }
        handshake(host, port, manager)
    }

    /**
     * Opens a TLS socket and checks the previously approved SHA-256 fingerprint
     * before allowing any Mumble protocol messages to be transmitted.
     */
    fun openPinned(host: String, port: Int, expectedFingerprint: String): SSLSocket {
        val manager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("Client certificates are not supported")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("Server sent no certificate")
                chain.first().checkValidity()
                if (fingerprint(chain.first()) != expectedFingerprint) {
                    throw CertificateException("Server certificate has changed (fingerprint mismatch)")
                }
            }
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<javax.net.ssl.TrustManager>(manager), null)
        val socket = connectPreferred(context, host, port, 6000)
        try {
            socket.soTimeout = 12000
            socket.startHandshake()
            return socket
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }
}
