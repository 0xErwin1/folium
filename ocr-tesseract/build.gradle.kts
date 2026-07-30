plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }

android {
    namespace = "com.folium.reader.ocr_tesseract"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
}

kotlin { jvmToolchain(17) }

dependencies { testImplementation(libs.junit) }

dependencies { implementation(project(":reader-core")) }
