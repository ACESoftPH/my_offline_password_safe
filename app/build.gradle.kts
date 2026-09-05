import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Optional release signing. Create `keystore.properties` in the project root with
 *
 *     storeFile=keystore/release.jks
 *     storePassword=…
 *     keyAlias=…
 *     keyPassword=…
 *
 * Both that file and the keystore itself are git-ignored. When they are absent
 * the release build still works, it just produces an unsigned APK — so a fresh
 * clone builds without needing anyone's private key.
 */
/**
 * Single source for the version, used both by `defaultConfig` and by the artifact
 * filenames below. They must not be able to disagree.
 *
 * `versionCode` is what Play treats as the identity of an upload; `versionName`
 * is a marketing string with no uniqueness guarantee, and two different builds
 * can legitimately carry the same one. So both go in the filename — a name
 * carrying only "1.1.0" cannot tell two 1.1.0 bundles apart, which is exactly
 * the confusion this is here to prevent.
 */
val appVersionName = "1.1.0"
val appVersionCode = 3

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile")
    ?.let { rootProject.file(it).exists() } == true

/**
 * Names every build artifact after the version it actually contains:
 *
 *     LockNest-1.1.0-3-release.aab
 *     LockNest-1.1.0-3-release.apk
 *     LockNest-1.1.0-3-debug.apk
 *
 * AGP appends the build type itself. Setting `archivesName` covers the bundle as
 * well as the APKs, which per-output renaming does not, and uses only stable
 * public API — no internal AGP classes and no deprecated `applicationVariants`.
 *
 * The filename is a convenience, not a source of truth: it is trivially wrong
 * after a careless copy or rename, and Play ignores it entirely and reads the
 * manifest. Verify an artifact you are about to upload with
 * `aapt2 dump badging <apk>` rather than trusting its name.
 */
base {
    archivesName.set("LockNest-$appVersionName-$appVersionCode")
}

android {
    namespace = "com.acesoftph.offlinepasswordwallet"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.acesoftph.offlinepasswordwallet"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // No cloud/analytics/crash SDKs are integrated. This is an offline-only app.
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Keep debuggable but still avoid verbose logging in code paths.
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.billing)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
