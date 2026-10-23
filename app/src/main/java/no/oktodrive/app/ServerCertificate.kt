package no.oktodrive.app

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

    private fun handshake(
        host: String,
        port: Int,
        trustManager: X509TrustManager
    ): X509Certificate {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustManager), null)
        val socket = context.socketFactory.createSocket() as SSLSocket
        socket.use {
            it.soTimeout = 6000
            it.connect(InetSocketAddress(host, port), 6000)
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
}
