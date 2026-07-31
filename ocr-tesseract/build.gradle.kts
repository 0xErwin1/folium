plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }

android {
    namespace = "com.folium.reader.ocr_tesseract"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":reader-core"))
    implementation(libs.tesseract4android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
