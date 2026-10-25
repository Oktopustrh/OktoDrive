package no.oktodrive.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Persistent Mumble TLS identity.
 *
 * Mumble's desktop client uses a self-signed RSA-2048 client certificate. We
 * mirror that here, but keep the PKCS#8 private key encrypted at rest with a
 * non-exportable AES key in Android Keystore. This avoids AndroidKeyStore RSA
 * signing quirks inside Conscrypt while keeping a stable identity across app
 * restarts and updates.
 */
internal object ClientIdentity {
    private const val PREFS = "oktodrive_mumble_identity"
    private const val PREF_PRIVATE = "rsa_private_v5"
    private const val PREF_CERT = "rsa_cert_v5"
    private const val WRAP_ALIAS = "oktodrive-mumble-wrap-v1"
    private const val CLIENT_ALIAS = "oktodrive-mumble-client-rsa-v5"

    fun keyManagers(): Array<KeyManager> {
        val identity = loadOrCreateIdentity()
        return arrayOf(FixedClientKeyManager(identity.first, identity.second))
    }

    private fun loadOrCreateIdentity(): Pair<PrivateKey, X509Certificate> {
        val prefs = OktoDriveApp.context().getSharedPreferences(PREFS, 0)
        val storedPrivate = prefs.getString(PREF_PRIVATE, null)
        val storedCert = prefs.getString(PREF_CERT, null)

        if (!storedPrivate.isNullOrBlank() && !storedCert.isNullOrBlank()) {
            try {
                val privateDer = decrypt(Base64.decode(storedPrivate, Base64.DEFAULT))
                val privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(privateDer))
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(
                        ByteArrayInputStream(Base64.decode(storedCert, Base64.DEFAULT))
                    ) as X509Certificate
                cert.checkValidity()
                verifyPair(privateKey, cert)
                return privateKey to cert
            } catch (_: Exception) {
                // Corrupt/stale identity: create a fresh one below.
            }
        }

        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = buildMumbleCertificate(pair)
        prefs.edit()
            .putString(PREF_PRIVATE, Base64.encodeToString(encrypt(pair.private.encoded), Base64.NO_WRAP))
            .putString(PREF_CERT, Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
            .apply()
        return pair.private to cert
    }

    private fun verifyPair(privateKey: PrivateKey, cert: X509Certificate) {
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(challenge)
        val signature = signer.sign()
        signer.initVerify(cert.publicKey)
        signer.update(challenge)
        check(signer.verify(signature)) { "Stored Mumble key does not match certificate" }
    }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + encrypted
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        require(blob.isNotEmpty())
        val ivSize = blob[0].toInt() and 0xff
        require(ivSize in 12..32 && blob.size > 1 + ivSize)
        val iv = blob.copyOfRange(1, 1 + ivSize)
        val encrypted = blob.copyOfRange(1 + ivSize, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    /** Build the same essential certificate shape as Mumble's SelfSignedCertificate.cpp. */
    private fun buildMumbleCertificate(pair: KeyPair): X509Certificate {
        val algorithm = sequence(oid("1.2.840.113549.1.1.5"), derNull()) // sha1WithRSAEncryption
        val name = sequence(set(sequence(oid("2.5.4.3"), utf8("Mumble User"))))
        val notBefore = Date(System.currentTimeMillis() - 60L * 60L * 1000L)
        val notAfter = Date(System.currentTimeMillis() + 20L * 365L * 24L * 60L * 60L * 1000L)

        val basicConstraints = extension(
            "2.5.29.19",
            critical = true,
            value = sequence() // CA defaults to FALSE
        )
        val clientAuth = extension(
            "2.5.29.37",
            critical = false,
            value = sequence(oid("1.3.6.1.5.5.7.3.2"))
        )
        val keyId = MessageDigest.getInstance("SHA-1").digest(pair.public.encoded)
        val subjectKeyId = extension("2.5.29.14", false, octetString(keyId))
        val netscapeComment = extension("2.16.840.1.113730.1.13", false, ia5("Generated by Mumble"))
        val extensions = explicit(3, sequence(basicConstraints, clientAuth, subjectKeyId, netscapeComment))

        val tbs = sequence(
            explicit(0, integer(BigInteger.valueOf(2))),
            integer(BigInteger.ONE),
            algorithm,
            name,
            sequence(time(notBefore), time(notAfter)),
            name,
            pair.public.encoded,
            extensions
        )

        val signer = Signature.getInstance("SHA1withRSA")
        signer.initSign(pair.private)
        signer.update(tbs)
        val signature = signer.sign()
        val certificateDer = sequence(tbs, algorithm, bitString(signature))

        return (CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificateDer)) as X509Certificate).apply {
            checkValidity()
            verify(pair.public)
        }
    }

    private fun extension(oidValue: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) sequence(oid(oidValue), derBoolean(true), octetString(value))
        else sequence(oid(oidValue), octetString(value))

    private fun sequence(vararg parts: ByteArray): ByteArray = tagged(0x30, concat(*parts))
    private fun set(vararg parts: ByteArray): ByteArray = tagged(0x31, concat(*parts))
    private fun explicit(tag: Int, value: ByteArray): ByteArray = tagged(0xA0 + tag, value)
    private fun integer(value: BigInteger): ByteArray = tagged(0x02, value.toByteArray())
    private fun utf8(value: String): ByteArray = tagged(0x0C, value.toByteArray(Charsets.UTF_8))
    private fun ia5(value: String): ByteArray = tagged(0x16, value.toByteArray(Charsets.US_ASCII))
    private fun octetString(value: ByteArray): ByteArray = tagged(0x04, value)
    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)
    private fun derBoolean(value: Boolean): ByteArray = tagged(0x01, byteArrayOf(if (value) 0xff.toByte() else 0x00))
    private fun bitString(value: ByteArray): ByteArray = tagged(0x03, byteArrayOf(0x00) + value)

    private fun time(date: Date): ByteArray {
        val year = SimpleDateFormat("yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(date).toInt()
        val pattern = if (year in 1950..2049) "yyMMddHHmmss'Z'" else "yyyyMMddHHmmss'Z'"
        val text = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)
        return tagged(if (year in 1950..2049) 0x17 else 0x18, text.toByteArray(Charsets.US_ASCII))
    }

    private fun oid(value: String): ByteArray {
        val arcs = value.split('.').map { it.toLong() }
        require(arcs.size >= 2)
        val body = ByteArrayOutputStream()
        body.write((arcs[0] * 40 + arcs[1]).toInt())
        for (arc in arcs.drop(2)) {
            val bytes = ArrayList<Int>()
            var v = arc
            bytes.add((v and 0x7f).toInt())
            v = v ushr 7
            while (v > 0) {
                bytes.add(0, ((v and 0x7f).toInt() or 0x80))
                v = v ushr 7
            }
            bytes.forEach { body.write(it) }
        }
        return tagged(0x06, body.toByteArray())
    }

    private fun tagged(tag: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        writeLength(out, body.size)
        out.write(body)
        return out.toByteArray()
    }

    private fun writeLength(out: ByteArrayOutputStream, length: Int) {
        if (length < 128) {
            out.write(length)
            return
        }
        val bytes = ArrayList<Int>()
        var value = length
        while (value > 0) {
            bytes.add(0, value and 0xff)
            value = value ushr 8
        }
        out.write(0x80 or bytes.size)
        bytes.forEach { out.write(it) }
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private class FixedClientKeyManager(
        private val privateKey: PrivateKey,
        private val certificate: X509Certificate
    ) : X509ExtendedKeyManager() {
        private fun supports(keyType: String?): Boolean =
            keyType?.uppercase(Locale.US)?.startsWith("RSA") == true

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            if (supports(keyType)) arrayOf(CLIENT_ALIAS) else null

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?
        ): String? = if (keyType?.any(::supports) == true) CLIENT_ALIAS else null

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?
        ): String? = if (keyType?.any(::supports) == true) CLIENT_ALIAS else null

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias == CLIENT_ALIAS) arrayOf(certificate) else null

        override fun getPrivateKey(alias: String?): PrivateKey? =
            if (alias == CLIENT_ALIAS) privateKey else null

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = null
    }
}
