plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "no.oktodrive.app"
    compileSdk = 35
    defaultConfig { applicationId = "no.oktodrive.app"; minSdk = 26; targetSdk = 35; versionCode = 1003; versionName = "0.1.3" }
    val signingPath = System.getenv("OKTODRIVE_KEYSTORE_PATH")
    if (!signingPath.isNullOrBlank()) {
        signingConfigs {
            create("persistentRelease") {
                storeFile = file(signingPath)
                storePassword = System.getenv("OKTODRIVE_STORE_PASSWORD")
                keyAlias = System.getenv("OKTODRIVE_KEY_ALIAS")
                keyPassword = System.getenv("OKTODRIVE_KEY_PASSWORD")
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("persistentRelease")
            }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
