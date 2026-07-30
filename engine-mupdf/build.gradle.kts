plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }

android {
    namespace = "com.folium.reader.engine_mupdf"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
}

kotlin { jvmToolchain(17) }

dependencies { testImplementation(libs.junit) }

dependencies { implementation(project(":reader-core")) }
