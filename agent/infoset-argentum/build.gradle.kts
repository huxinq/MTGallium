plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":agent:infoset-core"))
    implementation("org.mtgallium.argentum:ai")
    implementation("org.mtgallium.argentum:gym")
    implementation("org.mtgallium.argentum:gym-trainer")
    implementation("org.mtgallium.argentum:mtg-sdk")
    implementation("org.mtgallium.argentum:rules-engine")
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)
    testImplementation("org.mtgallium.argentum:mtg-sets")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
