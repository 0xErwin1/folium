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
    .filter { file -> file.invariantSeparatorsPath.contains("/src/") }
    .toList()

fun forbiddenAdapterReferences(projectPath: String): List<String> {
    val adapterPackages = listOf(
        "com.folium.reader.engine_mupdf",
        "com.folium.reader.ocr_tesseract"
    )
    return sourceFiles(projectPath).flatMap { file ->
        file.readLines().mapIndexedNotNull { index, line ->
            val adapterPackage = adapterPackages.firstOrNull { line.contains(it) }
            adapterPackage?.let { "${file.relativeTo(rootDir)}:${index + 1} references adapter package $it" }
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
    }
}
