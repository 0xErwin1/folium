plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

/**
 * Release signing material is read from the environment only, never from a committed file, so no
 * keystore or credential ever enters version control. All four values must be present; a partial
 * set is treated as absent so a half-configured CI job cannot silently produce an artifact signed
 * with unexpected material.
 */
/**
 * Trailing newlines survive a copy-paste into a secrets field, and a password with one is not the
 * password the keystore holds. Trimming here rather than in CI keeps the raw values masked in the
 * workflow log, since a trimmed copy no longer matches what the runner knows to redact.
 */
fun signingMaterial(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotBlank() }

val releaseKeystorePath: String? = signingMaterial("RELEASE_KEYSTORE_PATH")
val releaseKeystorePassword: String? = signingMaterial("RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = signingMaterial("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = signingMaterial("RELEASE_KEY_PASSWORD")

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

            // Compose in particular pays for shipping unoptimized: its runtime is heavily
            // generic and inlined, and without R8 none of that collapses. proguard-rules.pro
            // carries the keep rules the two JNI adapters need.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    // Unit tests run on the plain JVM, where android.os.Trace (behind androidx.tracing) is a stub
    // that throws on every call rather than a Robolectric shadow. Falling back to its defaults
    // keeps tracing a no-op here instead of failing every test that exercises a traced code path.
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
    sourceSets["androidTest"].assets.srcDir(rootProject.file("test-fixtures/pdf"))
}

room { schemaDirectory("$projectDir/schemas") }

kotlin { jvmToolchain(17) }

composeCompiler {
    stabilityConfigurationFile.set(rootProject.layout.projectDirectory.file("compose_stability.conf"))
}

dependencies {
    implementation(project(":reader-core"))
    implementation(project(":engine-mupdf"))
    implementation(project(":ocr-tesseract"))

    implementation(libs.androidx.tracing.ktx)

    implementation(libs.androidx.ink.authoring)
    implementation(libs.androidx.ink.brush)
    implementation(libs.androidx.ink.geometry)
    implementation(libs.androidx.ink.rendering)
    implementation(libs.androidx.ink.strokes)
    implementation(libs.androidx.input.motionprediction)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
