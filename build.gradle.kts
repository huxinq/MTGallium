import org.gradle.api.tasks.testing.Test
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.4.0" apply false
    kotlin("plugin.serialization") version "2.4.0" apply false
}

tasks.register("checkArchitecture") {
    group = "verification"
    description = "Check production dependency isolation for policies, models and numerical libraries."
    doLast {
        val semantics = ":agent:infoset-semantics"
        val planning = ":agent:infoset-planning"
        val models = setOf(":agent:mono-red-models", ":agent:neural-policy")
        val isolated = models + setOf(semantics, planning)
        val violations = mutableSetOf<String>()
        for (owner in subprojects) {
            for (name in listOf("compileClasspath", "runtimeClasspath")) {
                val configuration = owner.configurations.findByName(name) ?: continue
                for (component in configuration.incoming.resolutionResult.allComponents) {
                    val id = component.id as? ProjectComponentIdentifier
                    val local = id?.takeIf { it.build.buildPath == ":" }?.projectPath
                    if (local == owner.path) continue
                    val engine = id?.build?.buildPath == ":argentum-engine" ||
                        component.moduleVersion?.group == "org.mtgallium.argentum"
                    val application = local?.startsWith(":evaluation:") == true ||
                        local == ":research:workbench"
                    val outer = application || local?.startsWith(":integration:") == true
                    val forbidden = when {
                        owner.path in isolated -> engine || outer || when (owner.path) {
                            semantics -> local?.startsWith(":agent:") == true
                            planning -> local?.startsWith(":agent:") == true && local != semantics
                            else -> local in setOf(planning, ":agent:infoset-argentum", ":agent:argentum-policy")
                        }
                        owner.path.startsWith(":agent:") || owner.path.startsWith(":integration:") ->
                            application
                        else -> false
                    }
                    if (forbidden) violations += "${owner.path} $name depends on ${component.id.displayName}"
                }
            }
        }
        check(violations.isEmpty()) { violations.sorted().joinToString("\n") }
    }
}

subprojects {
    // Some tests read live repository files or environment inputs that are not
    // fully declared to Gradle. Keep local UP-TO-DATE behavior, but never restore
    // or store first-party test results in the cross-build task-output cache.
    tasks.withType<Test>().configureEach {
        outputs.doNotCacheIf("Test external inputs are not fully declared") { true }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
