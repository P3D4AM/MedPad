import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.medpad.tracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.medpad.tracker"
        minSdk = 24
        targetSdk = 34
        // develop test release: aligned with server v1.9.12 (see
        // DEVELOP-TEST-REPORT-fa.md). versionCode strictly increased
        // from 10 (v1.9.1) so it can upgrade an existing install.
        versionCode = 11
        versionName = "1.9.12-dev.1"
    }

    // ─── Signing (audit phase 2 hardening) ─────────────────────────────
    // The previous keystore AND its password were committed to a PUBLIC
    // repository, so that key is COMPROMISED and must never be reused.
    // No signing credential belongs in source control anymore.
    //
    // Configure the NEW release signing in one of two ways:
    //   1) environment variables:
    //        MEDPAD_STORE_FILE=/absolute/path/medpad-release.keystore
    //        MEDPAD_STORE_PASSWORD=...
    //        MEDPAD_KEY_ALIAS=...
    //        MEDPAD_KEY_PASSWORD=...
    //   2) keystore/medpad-release.properties (git-ignored), keys:
    //        storeFile, storePassword, keyAlias, keyPassword
    //
    // Without a complete configuration, `assembleRelease` produces an
    // UNSIGNED apk (app/build/outputs/apk/release/app-release-unsigned.apk)
    // and prints how to sign it — the build does NOT fail and does NOT
    // silently fall back to any default key.
    // NOTE: `java.util.Properties()` does not compile inside the android{}
    // lambda (the `java` name is shadowed there) — hence the top-level
    // import + plain `Properties()` (found during the develop-test build;
    // the script had never been compiled before).
    val medpadProps = Properties()
    val medpadPropsFile = rootProject.file("keystore/medpad-release.properties")
    if (medpadPropsFile.exists()) {
        medpadPropsFile.inputStream().use { medpadProps.load(it) }
        logger.lifecycle("MedPad signing: using keystore/medpad-release.properties")
    }
    fun signingProp(env: String, key: String): String? =
        System.getenv(env)?.takeIf { it.isNotBlank() } ?: medpadProps.getProperty(key)?.takeIf { it.isNotBlank() }

    signingConfigs {
        create("release") {
            val storePath = signingProp("MEDPAD_STORE_FILE", "storeFile")
            if (storePath != null) storeFile = file(storePath)
            storePassword = signingProp("MEDPAD_STORE_PASSWORD", "storePassword")
            keyAlias = signingProp("MEDPAD_KEY_ALIAS", "keyAlias")
            keyPassword = signingProp("MEDPAD_KEY_PASSWORD", "keyPassword")
        }
    }

    val releaseSigningReady = signingConfigs.getByName("release").let {
        it.storeFile != null && !it.storePassword.isNullOrBlank() && !it.keyAlias.isNullOrBlank() && !it.keyPassword.isNullOrBlank()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // Explicit unsigned build + instructions (never a silent default key).
                signingConfig = null
                logger.warn(
                    "MedPad: release signing is NOT configured -> the release apk will be UNSIGNED.\n" +
                    "  Set MEDPAD_STORE_FILE / MEDPAD_STORE_PASSWORD / MEDPAD_KEY_ALIAS / MEDPAD_KEY_PASSWORD\n" +
                    "  or create keystore/medpad-release.properties (see Android/ANDROID-SIGNING.md).\n" +
                    "  Sign the unsigned apk afterwards, e.g.:\n" +
                    "    apksigner sign --ks <keystore> --ks-key-alias <alias> --out app-release.apk app-release-unsigned.apk"
                )
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Audit phase 2: debug builds use the standard ANDROID DEBUG key.
            // (The old config signed debug builds with the RELEASE key — removed.)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Room (offline queue)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Map (osmdroid — open-source, no API key)
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
}
