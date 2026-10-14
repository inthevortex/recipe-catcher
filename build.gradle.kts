plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

tasks.register("verifyCoreIsolation") {
    group = "verification"
    description = "Verifies that the :core module has zero android.* or androidx.* imports"
    doLast {
        val coreSrcDir = file("core/src/main")
        if (!coreSrcDir.exists()) {
            println("No core/src/main found yet to scan.")
            return@doLast
        }
        val violations = mutableListOf<String>()
        coreSrcDir.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.forEach { file ->
            file.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import android.") || trimmed.startsWith("import androidx.")) {
                    violations.add("${file.relativeTo(projectDir)}: $trimmed")
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(":core module isolation violation! Found android/androidx imports:\n" + violations.joinToString("\n"))
        }
        println(":core isolation check passed: zero android.* or androidx.* imports found.")
    }
}
