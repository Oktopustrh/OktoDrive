package no.oktodrive.app

import java.net.URI

internal data class AppUpdate(
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val certificateSha256: String,
    val downloadUrl: String
)

internal object UpdatePolicy {
    fun rejection(update: AppUpdate, packageName: String, installedCode: Long,
                  sdk: Int, installedSigners: Set<String>): String? {
        if (update.applicationId != packageName) return "Oppdateringen gjelder en annen appvariant."
        if (update.versionCode <= installedCode) return "Du har allerede denne eller en nyere versjon."
        if (update.minSdk > sdk) return "Oppdateringen krever en nyere Android-versjon."
        val digest = update.certificateSha256.lowercase()
        if (!digest.matches(Regex("[0-9a-f]{64}")) || installedSigners != setOf(digest))
            return "Denne installasjonen har en annen signeringsnøkkel. Oppdateringen kan ikke installeres over den."
        val uri = try { URI(update.downloadUrl) } catch (_: Exception) { return "Ugyldig nedlastingsadresse." }
        if (uri.scheme != "https" || uri.host != "github.com" || uri.port != -1 ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            !uri.rawPath.startsWith("/Oktopustrh/OktoDrive/releases/download/") ||
            !uri.rawPath.endsWith(".apk") || uri.rawPath.contains("..") || uri.rawPath.contains('%'))
            return "Nedlastingsadressen er ikke en OktoDrive-utgivelse."
        return null
    }
}
