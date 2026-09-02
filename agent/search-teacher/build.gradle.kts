plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":agent:infoset-core"))
    api(project(":agent:infoset-argentum"))
    implementation("org.mtgallium.argentum:rules-engine")
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets.main {
    resources.srcDir(rootProject.file("fixtures"))
}
