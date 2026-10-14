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
        val androidGroup = Regex("""^(android|androidx|com\.android|com\.google\.android)(\..*)?$""")
        val core = project(":core")
        listOf("compileClasspath", "runtimeClasspath").forEach { configName ->
            core.configurations.getByName(configName).incoming.resolutionResult.allComponents.forEach { component ->
                val module = component.moduleVersion ?: return@forEach
                if (androidGroup.matches(module.group)) {
                    violations.add("core $configName: ${module.group}:${module.name}:${module.version}")
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(":core module isolation violation! Found Android imports or dependencies:\n" + violations.joinToString("\n"))
        }
        println(":core isolation check passed: zero android.* or androidx.* imports or dependencies found.")
    }
}
