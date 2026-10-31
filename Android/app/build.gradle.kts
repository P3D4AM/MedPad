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
        // dev.5 — phase 3 (background location + live panel status):
        // location upload queue (idempotent localId, no silent loss),
        // remote tracking policy (move/still intervals, motion threshold,
        // max age, on/off) with offline retention, boot restart, permission
        // state banner + reporting, panel live view + route history.
        // versionCode strictly increased from 15 so it upgrades an
        // existing same-signature install (this APK is signed with the
        // standard Android DEBUG key).
        versionCode = 17
        versionName = "1.9.13-dev.6"
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
        // Package 2 (phase 2 / task 3, B6): the app uses java.time.* (Instant
        // in ApiClient/CheckinSync/PlansSync/ReportsActivity) but minSdk is
        // 24 — java.time only exists natively from API 26. Without library
        // desugaring every Plans/Reports render crashed with NoClassDefFoundError
        // on Android 7.x. Desugaring rewrites those calls to bundled shims.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }

    testOptions {
        unitTests {
            // Robolectric needs inflated resources for the activity smoke tests
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Package 2: java.time on minSdk 24 (see compileOptions above)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

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

    // Package 2: local unit tests — Robolectric runs the real Android
    // framework on the JVM so the Plans/Reports screens can be smoke-tested
    // without a device (no emulator/KVM in this sandbox).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.6.1")
}
