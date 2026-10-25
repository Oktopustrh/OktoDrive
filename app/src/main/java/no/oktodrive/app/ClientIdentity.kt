package no.oktodrive.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Calendar
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.security.auth.x500.X500Principal

/**
 * Persistent per-installation TLS identity for Mumble.
 *
 * The private key is generated inside Android Keystore and is not exported.
 * The same self-signed client certificate is reused for every Mumble TLS
 * connection until the app's keystore entry is removed (for example by
 * clearing app data / uninstalling the app).
 */
internal object ClientIdentity {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "oktodrive-mumble-client"

    fun keyManagers(): Array<KeyManager> {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        ensureIdentity(keyStore)

        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore, null)
        return factory.keyManagers
    }

    private fun ensureIdentity(keyStore: KeyStore) {
        if (keyStore.containsAlias(ALIAS)) return

        val now = Calendar.getInstance()
        val notBefore = now.time
        val notAfter = Calendar.getInstance().apply {
            time = notBefore
            add(Calendar.YEAR, 20)
        }.time

        val serial = BigInteger(63, SecureRandom()).max(BigInteger.ONE)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(
                KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                KeyProperties.SIGNATURE_PADDING_RSA_PSS
            )
            .setCertificateSubject(X500Principal("CN=OktoDrive Mumble Client"))
            .setCertificateSerialNumber(serial)
            .setCertificateNotBefore(notBefore)
            .setCertificateNotAfter(notAfter)
            .setUserAuthenticationRequired(false)
            .build()

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
    }
}
