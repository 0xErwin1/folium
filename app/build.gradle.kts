plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android) }

android {
    namespace = "com.folium.reader"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.folium.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":reader-core"))
    implementation(project(":engine-mupdf"))
    implementation(project(":ocr-tesseract"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
