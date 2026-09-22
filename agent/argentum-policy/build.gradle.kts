plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":agent:infoset-semantics"))
    api(project(":agent:infoset-planning"))
    api(project(":agent:infoset-argentum"))
    implementation(project(":agent:mono-red-models"))
    api(project(":agent:neural-policy"))
    implementation("org.mtgallium.argentum:rules-engine")
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.mtgallium.argentum:gym")
    testImplementation("org.mtgallium.argentum:mtg-sdk")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets.main {
    resources.srcDir(rootProject.file("fixtures"))
}
