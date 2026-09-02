plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

dependencies {
    implementation(project(":agent:research-run"))
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

sourceSets.main {
    resources.srcDir(rootProject.file("fixtures"))
}

application {
    mainClass = "org.mtgallium.evaluation.argentum.ArgentumEvaluationKt"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
