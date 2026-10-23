plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "no.oktodrive.app"
    compileSdk = 35
    defaultConfig { applicationId = "no.oktodrive.app"; minSdk = 26; targetSdk = 35; versionCode = 1002; versionName = "0.1.2" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
