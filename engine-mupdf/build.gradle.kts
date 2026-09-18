plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }

android {
    namespace = "com.folium.reader.engine_mupdf"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets["androidTest"].assets.srcDir(rootProject.file("test-fixtures/pdf"))
    // Unit tests run on the plain JVM, where android.os.Trace (behind androidx.tracing) is a stub
    // that throws on every call rather than a Robolectric shadow. Falling back to its defaults
    // keeps tracing a no-op here instead of failing every test that exercises a traced code path.
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":reader-core"))
    implementation(libs.mupdf.fitz)
    implementation(libs.androidx.tracing.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
