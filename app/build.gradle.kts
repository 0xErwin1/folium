plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing material is read from the environment only, never from a committed file, so no
 * keystore or credential ever enters version control. All four values must be present; a partial
 * set is treated as absent so a half-configured CI job cannot silently produce an artifact signed
 * with unexpected material.
 */
val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val releaseKeystorePassword: String? = System.getenv("RELEASE_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias: String? = System.getenv("RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword: String? = System.getenv("RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }

val hasReleaseSigningMaterial =
    releaseKeystorePath != null &&
        releaseKeystorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null

/**
 * Local-development baseline. CI derives the published version from the git tags and the
 * Conventional Commits since the last one and injects it through the environment, so these literals
 * are never bumped by the pipeline and are only used when the environment says nothing.
 *
 * `.github/scripts/compute-version.sh` parses both names out of this file; renaming them means
 * updating that script too.
 */
val committedVersionCode = 1
val committedVersionName = "0.1.0"

val injectedVersionCode: Int? = System.getenv("FOLIUM_VERSION_CODE")?.trim()?.toIntOrNull()
val injectedVersionName: String? = System.getenv("FOLIUM_VERSION_NAME")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.folium.reader"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.folium.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = injectedVersionCode ?: committedVersionCode
        versionName = injectedVersionName ?: committedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (hasReleaseSigningMaterial) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Without release material the build stays green and falls back to the debug key; the
            // resulting APK is testing-only and CI labels it as such.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin { jvmToolchain(17) }

composeCompiler {
    stabilityConfigurationFile.set(rootProject.layout.projectDirectory.file("compose_stability.conf"))
}

dependencies {
    implementation(project(":reader-core"))
    implementation(project(":engine-mupdf"))
    implementation(project(":ocr-tesseract"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
