plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("java-test-fixtures")
}

dependencies {
    api(project(":agent:infoset-semantics"))
    implementation(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
