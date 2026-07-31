plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }

android {
    namespace = "com.folium.reader.engine_mupdf"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":reader-core"))
    implementation(libs.mupdf.fitz)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
