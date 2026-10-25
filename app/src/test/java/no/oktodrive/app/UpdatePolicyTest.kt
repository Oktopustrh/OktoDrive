package no.oktodrive.app

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val signer = "a".repeat(64)
    private val update = AppUpdate("no.oktodrive.app.dev", 1011, "0.1.11-dev", 24,
        signer, "https://github.com/Oktopustrh/OktoDrive/releases/download/dev-latest/OktoDrive-dev.apk")
    private fun rejected(candidate: AppUpdate) = UpdatePolicy.rejection(candidate,
        "no.oktodrive.app.dev", 1010, 30, setOf(signer))

    @Test fun compatibleNewerUpdateAccepted() { assertNull(rejected(update)) }
    @Test fun noDowngradesOrDuplicateUpdates() {
        assertNotNull(rejected(update.copy(versionCode = 1008)))
        assertNotNull(rejected(update.copy(versionCode = 1010)))
    }
    @Test fun wrongVariantOrSignerOrAndroidRejected() {
        assertNotNull(rejected(update.copy(applicationId = "no.oktodrive.app")))
        assertNotNull(rejected(update.copy(certificateSha256 = "b".repeat(64))))
        assertNotNull(rejected(update.copy(minSdk = 31)))
    }
    @Test fun maliciousOrUnrelatedDownloadsRejected() {
        for (url in listOf("http://github.com/Oktopustrh/OktoDrive/releases/download/x/a.apk",
            "https://github.com.evil.test/Oktopustrh/OktoDrive/releases/download/x/a.apk",
            "https://github.com/another/repo/releases/download/x/a.apk",
            "https://github.com/Oktopustrh/OktoDrive/releases/download/../a.apk",
            "https://github.com/Oktopustrh/OktoDrive/releases/download/%2e%2e/a.apk")) {
            assertNotNull(url, rejected(update.copy(downloadUrl = url)))
        }
    }
}
