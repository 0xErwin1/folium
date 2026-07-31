import org.gradle.api.artifacts.ProjectDependency
import java.io.File

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

allprojects {
    dependencyLocking { lockAllConfigurations() }
}

val approvedProjectGraph = mapOf(
    ":reader-core" to emptySet(),
    ":engine-mupdf" to setOf(":reader-core"),
    ":ocr-tesseract" to setOf(":reader-core"),
    ":app" to setOf(":reader-core", ":engine-mupdf", ":ocr-tesseract"),
    ":benchmark" to emptySet()
)

fun Project.projectDependencies(): Set<String> = configurations.flatMap { configuration ->
    configuration.dependencies.withType(ProjectDependency::class.java)
        .map { it.dependencyProject.path }
        .filter { it != path }
}.toSet()

fun sourceFiles(projectPath: String): List<File> = project(projectPath).projectDir.walkTopDown()
    .filter { it.isFile && it.extension in setOf("kt", "java") }
    .filter { file -> file.invariantSeparatorsPath.contains("/src/main/") }
    .toList()

fun forbiddenAdapterReferences(projectPath: String): List<String> {
    val forbiddenReferences = listOf(
        "com.folium.reader.engine_mupdf",
        "com.folium.reader.ocr_tesseract",
        "com.artifex",
        "MuPDF",
        "com.googlecode.tesseract",
        "TessBaseAPI"
    )
    return sourceFiles(projectPath).flatMap { file ->
        file.readLines().mapIndexedNotNull { index, line ->
            val forbidden = forbiddenReferences.firstOrNull { line.contains(it) }
            forbidden?.let { "${file.relativeTo(rootDir)}:${index + 1} references forbidden implementation type $it" }
        }
    }
}

fun architectureViolations(files: List<File>): List<String> {
    val forbiddenReferences = listOf("com.artifex", "MuPDF", "com.googlecode.tesseract", "TessBaseAPI")
    return files.flatMap { file ->
        file.readLines().mapIndexedNotNull { index, line ->
            forbiddenReferences.firstOrNull { line.contains(it) }?.let { "${file.name}:${index + 1} references $it" }
        }
    }
}

fun androidSafViolations(files: List<File>): List<String> = files.flatMap { file ->
    file.readLines().mapIndexedNotNull { index, line ->
        if (Regex("\\b(android\\.|androidx\\.|Uri\\b|ContentResolver\\b|Cursor\\b|DocumentsContract\\b)").containsMatchIn(line)) {
            "${file.name}:${index + 1} leaks Android SAF type"
        } else null
    }
}

val forbiddenManifestPermissions = listOf(
    "android.permission.INTERNET",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE"
)

fun manifestForbiddenPermissions(manifestText: String): List<String> =
    forbiddenManifestPermissions.filter { manifestText.contains(it) }

fun manifestFilesToScan(): List<File> = listOf(
    rootProject.file("app/src/main/AndroidManifest.xml"),
    rootProject.file("app/src/debug/AndroidManifest.xml")
)

tasks.register("verifyArchitecture") {
    group = "verification"
    description = "Validates the approved module graph and neutral-core adapter boundaries."

    doLast {
        val actualProjects = rootProject.allprojects.map { it.path }.toSet() - ":"
        val expectedProjects = approvedProjectGraph.keys
        check(actualProjects == expectedProjects) {
            "Approved project set mismatch. Missing: ${expectedProjects - actualProjects}; unexpected: ${actualProjects - expectedProjects}"
        }

        approvedProjectGraph.forEach { (path, expectedDependencies) ->
            val actualDependencies = project(path).projectDependencies()
            check(actualDependencies == expectedDependencies) {
                "$path project dependency mismatch. Missing: ${expectedDependencies - actualDependencies}; forbidden: ${actualDependencies - expectedDependencies}"
            }
        }

        val coreViolations = forbiddenAdapterReferences(":reader-core")
        check(coreViolations.isEmpty()) {
            "Neutral reader-core must not expose or depend on adapter implementation packages:\n${coreViolations.joinToString("\n")}"
        }
        val androidCoreViolations = androidSafViolations(sourceFiles(":reader-core"))
        check(androidCoreViolations.isEmpty()) {
            "Neutral reader-core must not expose Android or SAF types:\n${androidCoreViolations.joinToString("\n")}"
        }
        val manifestFilesScanned = mutableListOf<String>()
        val manifestViolations = manifestFilesToScan().flatMap { manifestFile ->
            manifestFilesScanned += manifestFile.relativeTo(rootDir).invariantSeparatorsPath
            manifestForbiddenPermissions(manifestFile.readText())
                .map { permission -> "${manifestFile.relativeTo(rootDir).invariantSeparatorsPath} declares forbidden permission $permission" }
        }
        check(manifestViolations.isEmpty()) {
            "App manifest declares forbidden permissions:\n${manifestViolations.joinToString("\n")}"
        }
        // Recorded from the same loop that computes manifestViolations, so verifyArchitectureNegative
        // can prove this task actually consumed manifestFilesToScan()'s output rather than merely
        // asserting on the helper's return value in isolation.
        val scannedManifestsReport = layout.buildDirectory.file("architecture/scanned-manifests.txt").get().asFile
        scannedManifestsReport.parentFile.mkdirs()
        scannedManifestsReport.writeText(manifestFilesScanned.joinToString("\n"))
        val appViolations = forbiddenAdapterReferences(":app")
        check(appViolations.isEmpty()) {
            "App source must not import or reference concrete adapter implementation packages directly:\n${appViolations.joinToString("\n")}"
        }

        val nonAdapterViolations = approvedProjectGraph.keys
            .filter { it !in setOf(":engine-mupdf", ":ocr-tesseract") }
            .flatMap(::forbiddenAdapterReferences)
        check(nonAdapterViolations.isEmpty()) {
            "Only :engine-mupdf may reference Artifex/MuPDF and only :ocr-tesseract may reference Tesseract types:\n${nonAdapterViolations.joinToString("\n")}"
        }
    }
}

tasks.register("verifyArchitectureNegative") {
    group = "verification"
    description = "Proves the Artifex boundary rejects a forbidden source reference in an isolated copy."
    dependsOn("verifyArchitecture")

    doLast {
        val isolated = layout.buildDirectory.dir("architecture-negative").get().asFile
        isolated.deleteRecursively()
        isolated.mkdirs()
        val forbidden = isolated.resolve("Forbidden.kt")
        forbidden.writeText("import com.artifex.mupdf.fitz.Document\nimport com.googlecode.tesseract.android.TessBaseAPI\n")
        val violations = architectureViolations(listOf(forbidden))
        check(violations.size == 2) { "Architecture guard did not reject isolated adapter imports" }
        val safLeak = isolated.resolve("AndroidLeak.kt")
        safLeak.writeText("import android.net.Uri\n")
        check(androidSafViolations(listOf(safLeak)).size == 1) { "Architecture guard did not reject isolated Android SAF import" }
        forbiddenManifestPermissions.forEach { permission ->
            val fixtureManifest = isolated.resolve("Manifest-${permission.substringAfterLast('.')}.xml")
            fixtureManifest.writeText(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.folium.reader.fixture">
                    <uses-permission android:name="$permission" />
                    <application />
                </manifest>
                """.trimIndent()
            )
            val detected = manifestForbiddenPermissions(fixtureManifest.readText())
            check(detected == listOf(permission)) { "Permission guard did not reject isolated fixture manifest declaring $permission" }
        }

        val scannedManifestsReport = layout.buildDirectory.file("architecture/scanned-manifests.txt").get().asFile
        check(scannedManifestsReport.exists()) {
            "verifyArchitecture did not record which manifests it scanned; it must run before verifyArchitectureNegative"
        }
        val actuallyScannedManifests = scannedManifestsReport.readLines().filter { it.isNotBlank() }
        check(actuallyScannedManifests == listOf("app/src/main/AndroidManifest.xml", "app/src/debug/AndroidManifest.xml")) {
            "verifyArchitecture must consume manifestFilesToScan() and scan both the main and debug manifests, " +
                "actually scanned: $actuallyScannedManifests"
        }
    }
}
