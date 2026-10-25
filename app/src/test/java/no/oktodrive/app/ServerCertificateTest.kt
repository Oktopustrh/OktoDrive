package no.oktodrive.app

import org.junit.Assert.*
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import java.net.InetSocketAddress

class ServerCertificateTest {
    private fun cert(name: String): X509Certificate =
        javaClass.getResourceAsStream("/$name").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    @Test fun exceptionIsRestrictedToExactEndpoint() {
        assertTrue(ServerCertificate.usesTemporaryPin("ts.veiensvenner.no", 64738))
        assertTrue(ServerCertificate.usesTemporaryPin("TS.VEIENSVENNER.NO", 64738))
        for (host in listOf("64.227.113.55", "other.example", "ts.veiensvenner.no.evil", "ts.veiensvenner.no.")) {
            assertFalse(ServerCertificate.usesTemporaryPin(host, 64738))
        }
        assertFalse(ServerCertificate.usesTemporaryPin("ts.veiensvenner.no", 443))
    }

    @Test fun currentLeafAccepted() {
        ServerCertificate.checkPinnedCertificate(arrayOf(cert("mumble-current.pem")))
    }

    @Test(expected = CertificateException::class)
    fun emptyChainRejected() {
        ServerCertificate.checkPinnedCertificate(emptyArray())
    }

    @Test(expected = CertificateException::class)
    fun differentCertificateRejected() {
        ServerCertificate.checkPinnedCertificate(arrayOf(cert("different.pem")))
    }

    // Explicit opt-in integration test: TLS only, no Mumble login or credentials.
    @Test fun liveTlsComparison() {
        org.junit.Assume.assumeTrue(System.getenv("OKTODRIVE_LIVE_TLS_TEST") == "1")
        val host = "ts.veiensvenner.no"
        val context = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        (context.socketFactory.createSocket() as SSLSocket).use {
            it.soTimeout = 6000
            it.sslParameters = it.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            it.connect(InetSocketAddress(host, 64738), 6000)
            try {
                it.startHandshake()
                fail("Normal CA/hostname validation unexpectedly accepted self-signed certificate")
            } catch (expected: javax.net.ssl.SSLHandshakeException) {
                println("Normal validation rejected: " + expected.message)
            }
        }
        ServerCertificate.open(host, 64738).use {
            assertEquals(ServerCertificate.TEMPORARY_SHA256,
                ServerCertificate.fingerprint(it.session.peerCertificates.first() as X509Certificate))
            println("Exact pin TLS handshake succeeded: " + it.session.protocol)
        }
    }
}
