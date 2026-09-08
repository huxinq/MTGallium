import java.io.File
import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

val scenarioExecutionTag = "scenario-execution"
val publicSourceTag = "public-source"

dependencies {
    implementation(project(":agent:research-run"))
    implementation(project(":agent:infoset-core"))
    implementation(project(":agent:infoset-argentum"))
    implementation(project(":agent:search-teacher"))
    implementation("org.mtgallium.argentum:ai")
    implementation("org.mtgallium.argentum:gym")
    implementation("org.mtgallium.argentum:gym-trainer")
    implementation("org.mtgallium.argentum:mtg-sdk")
    implementation("org.mtgallium.argentum:mtg-sets")
    implementation("org.mtgallium.argentum:rules-engine")
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "org.mtgallium.evaluation.searchteacher.SearchTeacherEvaluationKt"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}

// The ordinary fast lane excludes long-running stateful scenarios; the full test task includes them.
tasks.register<Test>("fastTest") {
    group = "verification"
    description = "Runs bounded Search Teacher tests without long-running stateful scenarios."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        excludeTags(scenarioExecutionTag)
    }
}

// This lane is an explicit public-source contract. Its tests are tagged because their
// semantic prerequisites are first-party source or synthetic fixtures in this checkout;
// it is not a missing-resource fallback for the ordinary private-oriented fast lane.
tasks.register<Test>("publicSourceTest") {
    group = "verification"
    description = "Runs explicitly classified self-contained public-source Search Teacher tests."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags(publicSourceTag)
        excludeTags(scenarioExecutionTag)
    }
}

tasks.register<Test>("scenarioTest") {
    group = "verification"
    description = "Runs stateful Search Teacher games, multi-case diagnostics, proofs, and benchmarks."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags(scenarioExecutionTag)
    }
}

// Preserve Gradle's actual dependency order and every artifact even when two
// modules have the same jar basename (agent and evaluation both use search-teacher).
// The private build wrapper copies these with distinct ordinal names.
val researchRuntimeClasspath = configurations.named("runtimeClasspath")
tasks.register("researchRuntimeClasspath") {
    group = "distribution"
    description = "Builds and records the ordered jar runtime for a private attested research bundle."
    dependsOn(tasks.named("jar"), researchRuntimeClasspath)
    val destination = layout.buildDirectory.file("research/runtime-classpath.txt")
    outputs.file(destination)
    doLast {
        val applicationJar = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val artifacts = (listOf(applicationJar) + researchRuntimeClasspath.get().files).distinct()
        require(artifacts.all { it.isFile && it.extension == "jar" })
        destination.get().asFile.apply {
            parentFile.mkdirs()
            writeText(artifacts.joinToString(File.pathSeparator) { it.absolutePath } + "\n")
        }
    }
}
