plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
}

android {
    namespace = "no.oktodrive.app"
    compileSdk = 35
    defaultConfig { applicationId = "no.oktodrive.app"; minSdk = 26; targetSdk = 35; versionCode = 1007; versionName = "0.1.7" }

    val releaseSigningPath = System.getenv("OKTODRIVE_KEYSTORE_PATH")
    val testSigningPath = System.getenv("OKTODRIVE_TEST_KEYSTORE_PATH")

    signingConfigs {
        if (!releaseSigningPath.isNullOrBlank()) {
            create("persistentRelease") {
                storeFile = file(releaseSigningPath)
                storePassword = System.getenv("OKTODRIVE_STORE_PASSWORD")
                keyAlias = System.getenv("OKTODRIVE_KEY_ALIAS")
                keyPassword = System.getenv("OKTODRIVE_KEY_PASSWORD")
            }
        }
        if (!testSigningPath.isNullOrBlank()) {
            create("persistentTest") {
                storeFile = file(testSigningPath)
                storePassword = System.getenv("OKTODRIVE_TEST_STORE_PASSWORD")
                keyAlias = System.getenv("OKTODRIVE_TEST_KEY_ALIAS")
                keyPassword = System.getenv("OKTODRIVE_TEST_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            if (!testSigningPath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("persistentTest")
            }
        }
        getByName("release") {
            if (!releaseSigningPath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("persistentRelease")
            }
        }
    }

    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}
