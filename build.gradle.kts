import org.gradle.api.artifacts.ProjectDependency
import java.io.File

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
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

    doLast {
        val isolated = layout.buildDirectory.dir("architecture-negative").get().asFile
        isolated.deleteRecursively()
        isolated.mkdirs()
        val forbidden = isolated.resolve("Forbidden.kt")
        forbidden.writeText("import com.artifex.mupdf.fitz.Document\nimport com.googlecode.tesseract.android.TessBaseAPI\n")
        val violations = architectureViolations(listOf(forbidden))
        check(violations.size == 2) { "Architecture guard did not reject isolated adapter imports" }
    }
}
