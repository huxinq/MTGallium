pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "mtgallium"

include(":evaluation:argentum")
include(":agent:infoset-semantics")
include(":agent:infoset-planning")
include(":agent:infoset-argentum")
include(":agent:mono-red-models")
include(":agent:neural-policy")
include(":agent:argentum-policy")
include(":research:workbench")
include(":integration:argentum-policy")

includeBuild("third_party/argentum-engine") {
    dependencySubstitution {
        substitute(module("org.mtgallium.argentum:ai")).using(project(":ai"))
        substitute(module("org.mtgallium.argentum:gym")).using(project(":gym"))
        substitute(module("org.mtgallium.argentum:gym-trainer")).using(project(":gym-trainer"))
        substitute(module("org.mtgallium.argentum:mtg-sdk")).using(project(":mtg-sdk"))
        substitute(module("org.mtgallium.argentum:mtg-sets")).using(project(":mtg-sets"))
        substitute(module("org.mtgallium.argentum:rules-engine")).using(project(":rules-engine"))
        substitute(module("org.mtgallium.argentum:game-server")).using(project(":game-server"))
    }
}
