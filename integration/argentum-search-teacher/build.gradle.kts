plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(project(":agent:infoset-argentum"))
    implementation(project(":agent:search-teacher"))
    implementation("org.mtgallium.argentum:ai")
    implementation("org.mtgallium.argentum:game-server")
    implementation("org.mtgallium.argentum:gym")
    implementation("org.mtgallium.argentum:mtg-sdk")
    implementation("org.mtgallium.argentum:rules-engine")
    implementation(libs.spring.boot.starter)
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.mtgallium.argentum:mtg-sets")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "org.mtgallium.integration.searchteacher.ArgentumSearchTeacherApplicationKt"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir.resolve("third_party/argentum-engine")
}
