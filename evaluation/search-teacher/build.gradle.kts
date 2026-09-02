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

// Focused production-data witness for composing a new diagnostic from the stable corrected-neural
// preparation boundary. It validates current corpus/order/configuration and a no-op cohort split,
// but performs no optimizer update, training, or artifact write.
tasks.register<Test>("correctedNeuralDiagnosticHarnessTest") {
    group = "verification"
    description = "Validates the reusable corrected-neural diagnostic composition boundary."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching(
            "org.mtgallium.evaluation.searchteacher.CorrectedNeuralDiagnosticHarnessTest.*"
        )
    }
    outputs.upToDateWhen { false }
}

// Launch gate for the completed issue-0031 experiment. This deliberately selects the tiny
// invariant witnesses rather than the production-sized corpus/training tests in the same classes.
// The companion preflight suite runs the production corpus/order/reference preparation path with
// zero optimizer updates; the durable command calls the same preparation function before training.
tasks.register<Test>("neuralCohortContinuationPreflightTest") {
    group = "verification"
    description = "Runs the cheap issue-0031 launch-validity witnesses without research compute."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching(
            "org.mtgallium.evaluation.searchteacher.Issue0031NeuralCohortContinuationDiagnosticTest.*"
        )
        includeTestsMatching(
            "org.mtgallium.evaluation.searchteacher.NeuralCohortGradientDiagnosticTest.*"
        )
        includeTestsMatching(
            "org.mtgallium.evaluation.searchteacher.NeuralBehavioralCloningTest." +
                "candidate update scale changes only candidate projection parameter steps"
        )
        includeTestsMatching(
            "org.mtgallium.evaluation.searchteacher.NeuralBehavioralCloningTest." +
                "sparse Adam exposure counts actual aggregated parameter update calls"
        )
        includeTestsMatching(
            "org.mtgallium.evaluation.searchteacher.NeuralBehavioralCloningTest." +
                "sparse Adam checkpoint restores the exact next update without branch aliasing"
        )
        includeTestsMatching(
            "org.mtgallium.evaluation.searchteacher.NeuralBehavioralCloningTest." +
                "semantic entity ordinals survive candidate feature projection"
        )
        includeTestsMatching(
            "org.mtgallium.evaluation.searchteacher.NeuralBehavioralCloningTest." +
                "ordered candidate arrays retain their element positions"
        )
        includeTestsMatching(
            "org.mtgallium.evaluation.searchteacher.SearchTeacherInterfaceContractTest." +
                "suite catalog contains technical commands and rejects removed process commands"
        )
        includeTestsMatching(
            "org.mtgallium.evaluation.searchteacher.SearchTeacherInterfaceContractTest." +
                "exploratory outputs stay below work even through link redirects"
        )
    }
    // A preflight command should execute its witnesses even when Gradle has a prior test result.
    outputs.upToDateWhen { false }
}

tasks.named("run") {
    mustRunAfter("neuralCohortContinuationPreflightTest")
}

tasks.register<Test>("neuralAnchorCrossingPreflightTest") {
    group = "verification"
    description = "Validates retained fork restoration and issue-0034 one-step analysis seams."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching(
            "org.mtgallium.evaluation.searchteacher.Issue0034NeuralAnchorCrossingDiagnosticTest.*"
        )
        includeTestsMatching(
            "org.mtgallium.evaluation.searchteacher.NeuralCohortGradientDiagnosticTest.*"
        )
        includeTestsMatching(
            "org.mtgallium.evaluation.searchteacher.NeuralBehavioralCloningTest." +
                "sparse Adam checkpoint restores the exact next update without branch aliasing"
        )
        includeTestsMatching(
            "org.mtgallium.evaluation.searchteacher.SearchTeacherInterfaceContractTest." +
                "suite catalog contains technical commands and rejects removed process commands"
        )
        includeTestsMatching(
            "org.mtgallium.evaluation.searchteacher.SearchTeacherInterfaceContractTest." +
                "exploratory outputs stay below work even through link redirects"
        )
    }
    outputs.upToDateWhen { false }
}

tasks.named("run") {
    mustRunAfter("neuralAnchorCrossingPreflightTest")
}

tasks.register<Test>("neuralHeldOutGeneralizationPreflightTest") {
    group = "verification"
    description = "Validates issue-0031 fitted-model restoration and issue-0022 held-out identity."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching(
            "org.mtgallium.evaluation.searchteacher.Issue0035NeuralHeldOutGeneralizationDiagnosticTest.*"
        )
        includeTestsMatching(
            "org.mtgallium.evaluation.searchteacher.SearchTeacherInterfaceContractTest." +
                "suite catalog contains technical commands and rejects removed process commands"
        )
        includeTestsMatching(
            "org.mtgallium.evaluation.searchteacher.SearchTeacherInterfaceContractTest." +
                "exploratory outputs stay below work even through link redirects"
        )
    }
    outputs.upToDateWhen { false }
}

tasks.named("run") {
    mustRunAfter("neuralHeldOutGeneralizationPreflightTest")
}
