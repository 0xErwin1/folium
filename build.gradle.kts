import org.gradle.api.artifacts.ProjectDependency

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

allprojects {
    dependencyLocking { lockAllConfigurations() }
}

gradle.projectsEvaluated {
    val expected = mapOf(
        ":reader-core" to emptySet(),
        ":engine-mupdf" to setOf(":reader-core"),
        ":ocr-tesseract" to setOf(":reader-core"),
        ":app" to setOf(":reader-core", ":engine-mupdf", ":ocr-tesseract"),
        ":benchmark" to emptySet()
    )
    expected.forEach { (path, allowed) ->
        val actual = project(path).configurations.flatMap { configuration ->
            configuration.dependencies.withType(ProjectDependency::class.java)
                .map { it.dependencyProject.path }
                .filter { it != path }
        }.toSet()
        check(actual.all { it in allowed }) { "$path has forbidden project dependencies: ${actual - allowed}" }
        check(allowed.all { it in actual }) { "$path is missing required project dependencies: ${allowed - actual}" }
    }
}

tasks.register("verifyArchitecture") {
    group = "verification"
    description = "Validates the approved production module dependency graph."
}
