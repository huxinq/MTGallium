import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.4.0" apply false
    kotlin("plugin.serialization") version "2.4.0" apply false
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
