package no.oktodrive.app

import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

internal object ServerCertificate {
    // TEMPORARY diagnostic exception, fetched from the live endpoint 2026-10-25.
    // SHA-256 of the complete DER leaf certificate, NOT its public key.
    // TODO: Remove this exception once ts.veiensvenner.no has a public CA
    // certificate and restore normal CA + hostname validation for this endpoint.
    internal const val TEMPORARY_SHA256 =
        "13:B6:8D:79:82:77:EC:85:E9:E3:71:47:7B:60:AF:29:51:67:5F:8E:85:F2:0D:22:98:84:94:28:03:9C:77:A3"

    internal fun usesTemporaryPin(host: String, port: Int): Boolean =
        host.equals("ts.veiensvenner.no", ignoreCase = true) && port == 64738

    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it.toInt() and 0xff) }

    internal fun checkPinnedCertificate(chain: Array<X509Certificate>) {
        if (chain.isEmpty()) throw CertificateException("Server sent no certificate")
        chain.first().checkValidity()
        if (fingerprint(chain.first()) != TEMPORARY_SHA256) {
            throw CertificateException("Server certificate SHA-256 mismatch; connection blocked")
        }
    }

    fun open(host: String, port: Int, keyManagers: Array<javax.net.ssl.KeyManager>? = null): SSLSocket {
        val pinned = usesTemporaryPin(host, port)
        val context = SSLContext.getInstance("TLS")
        if (pinned) {
            val manager = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    throw CertificateException("Client certificates are not supported")
                }
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    checkPinnedCertificate(chain)
                }
            }
            context.init(keyManagers, arrayOf<javax.net.ssl.TrustManager>(manager), null)
        } else {
            context.init(keyManagers, null, null) // Platform CA trust; no global TLS changes.
        }
        val socket = context.socketFactory.createSocket() as SSLSocket
        try {
            socket.soTimeout = 12000
            if (!pinned) {
                socket.sslParameters = socket.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                }
            }
            socket.connect(InetSocketAddress(host, port), 6000)
            socket.startHandshake() // No credentials or Mumble messages before validation.
            return socket
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) { }
            throw e
        }
    }
}
