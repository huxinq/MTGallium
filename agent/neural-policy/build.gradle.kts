plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":agent:infoset-semantics"))
    implementation(libs.kotlinx.serialization.json)
    implementation("com.microsoft.onnxruntime:onnxruntime:1.29.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
