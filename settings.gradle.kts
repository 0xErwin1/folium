pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral(); maven("https://maven.ghostscript.com"); maven("https://jitpack.io") } }
rootProject.name = "Folium"
include(":app", ":reader-core", ":engine-mupdf", ":ocr-tesseract", ":benchmark")
