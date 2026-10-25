package no.oktodrive.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Persistent per-installation TLS identity for Mumble.
 *
 * The private key stays inside Android Keystore. A Mumble-compatible client
 * certificate is generated once, stored as public DER data, and reused on every
 * TLS connection so the server sees the same identity after app restarts.
 */
internal object ClientIdentity {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "oktodrive-mumble-client-v4"
    private const val PREFS = "oktodrive_mumble_identity"
    private const val PREF_CERT_DER = "client_cert_v4_der"

    fun keyManagers(): Array<KeyManager> {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val entry = ensureKey(keyStore)
        val certificate = loadOrCreateCertificate(entry)
        return arrayOf(FixedClientKeyManager(entry.privateKey, certificate))
    }

    private fun ensureKey(keyStore: KeyStore): KeyStore.PrivateKeyEntry {
        val existing = keyStore.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) return existing

        val now = Calendar.getInstance()
        val generatedNotBefore = now.time
        val generatedNotAfter = Calendar.getInstance().apply {
            time = generatedNotBefore
            add(Calendar.YEAR, 20)
        }.time

        val serial = BigInteger(63, SecureRandom()).max(BigInteger.ONE)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512
            )
            .setCertificateSubject(X500Principal("CN=OktoDrive Mumble Client"))
            .setCertificateSerialNumber(serial)
            .setCertificateNotBefore(generatedNotBefore)
            .setCertificateNotAfter(generatedNotAfter)
            .setUserAuthenticationRequired(false)
            .build()

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }

        return keyStore.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
    }

    private fun loadOrCreateCertificate(entry: KeyStore.PrivateKeyEntry): X509Certificate {
        val prefs = OktoDriveApp.context().getSharedPreferences(PREFS, 0)
        val stored = prefs.getString(PREF_CERT_DER, null)
        if (!stored.isNullOrBlank()) {
            try {
                val bytes = Base64.decode(stored, Base64.DEFAULT)
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
                if (cert.publicKey.encoded.contentEquals(entry.certificate.publicKey.encoded)) {
                    cert.checkValidity()
                    return cert
                }
            } catch (_: Exception) {
                // Regenerate below if the stored public certificate is unreadable or stale.
            }
        }

        val cert = buildMumbleCertificate(entry)
        prefs.edit()
            .putString(PREF_CERT_DER, Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
            .apply()
        return cert
    }

    private fun buildMumbleCertificate(entry: KeyStore.PrivateKeyEntry): X509Certificate {
        val now = Calendar.getInstance()
        val notBefore = Calendar.getInstance().apply {
            time = now.time
            add(Calendar.DAY_OF_YEAR, -1)
        }.time
        val notAfter = Calendar.getInstance().apply {
            time = now.time
            add(Calendar.YEAR, 20)
        }.time

        val subject = X500Name("CN=OktoDrive Mumble Client")
        val serial = BigInteger(127, SecureRandom()).max(BigInteger.ONE)
        val builder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            entry.certificate.publicKey
        )

        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth)
        )
        builder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            JcaX509ExtensionUtils().createSubjectKeyIdentifier(entry.certificate.publicKey)
        )

        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(entry.privateKey)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer)).apply {
            checkValidity()
            verify(entry.certificate.publicKey)
        }
    }

    private class FixedClientKeyManager(
        private val privateKey: PrivateKey,
        private val certificate: X509Certificate
    ) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            if (keyType.equals("EC", ignoreCase = true) || keyType.equals("ECDSA", ignoreCase = true)) {
                arrayOf(ALIAS)
            } else {
                null
            }

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?
        ): String? = if (keyType?.any { it.equals("EC", true) || it.equals("ECDSA", true) } == true) ALIAS else null

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?
        ): String? = if (keyType?.any { it.equals("EC", true) || it.equals("ECDSA", true) } == true) ALIAS else null

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias == ALIAS) arrayOf(certificate) else null

        override fun getPrivateKey(alias: String?): PrivateKey? =
            if (alias == ALIAS) privateKey else null

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = null
    }
}
