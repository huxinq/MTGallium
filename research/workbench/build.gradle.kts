import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

dependencies {
    implementation(project(":agent:infoset-semantics"))
    implementation(project(":agent:neural-policy"))
    implementation(project(":agent:infoset-planning"))
    implementation(project(":agent:infoset-argentum"))
    implementation(project(":agent:argentum-policy"))
    implementation(project(":agent:value-models"))
    implementation("org.mtgallium.argentum:ai")
    implementation("org.mtgallium.argentum:gym")
    implementation("org.mtgallium.argentum:mtg-sdk")
    implementation("org.mtgallium.argentum:mtg-sets")
    implementation("org.mtgallium.argentum:rules-engine")
    implementation(libs.kotlinx.serialization.json)
    implementation("org.apache.commons:commons-math3:3.6.1")
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation("org.mtgallium.argentum:game-server")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application { mainClass = "org.mtgallium.research.workbench.ResearchCliKt" }

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}

tasks.test { maxHeapSize = "1g" }

tasks.register<Test>("searchBench") {
    dependsOn(tasks.named("testClasses"))
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("org.mtgallium.research.workbench.RefactorSearchCpuProbeTest") }
    environment("MTG_RUN_REFACTOR_CPU_PROBE", "1")
    outputs.upToDateWhen { false }
}

// Include dependency changes when refreshing the launcher's classpath.
val researchJava = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
tasks.register("researchClasspath") {
    dependsOn(tasks.named("classes"), configurations.runtimeClasspath)
    val runtime = sourceSets["main"].runtimeClasspath
    val destination = layout.buildDirectory.file("research/runtime.json")
    inputs.files(runtime)
    inputs.property("javaExecutable", researchJava.map { it.executablePath.asFile.absolutePath })
    outputs.file(destination)
    doLast {
        val file = destination.get().asFile
        file.parentFile.mkdirs()
        file.writeText(groovy.json.JsonOutput.toJson(mapOf(
            "java" to researchJava.get().executablePath.asFile.absolutePath,
            "classpath" to runtime.asPath,
        )) + "\n")
    }
}
