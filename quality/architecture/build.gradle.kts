plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// RepositoryArchitectureConformanceTest deliberately reads the live working tree,
// including untracked source and documentation outside this Gradle project's
// declared inputs. The suite is small, so always rerun it instead of allowing a
// stale UP-TO-DATE result to certify a changed repository.
tasks.withType<Test>().configureEach {
    outputs.upToDateWhen { false }
}
