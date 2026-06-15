pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "AsyncTNT"

include(":api", ":common", ":core", ":compat-folia", ":tester")
project(":compat-folia").projectDir = file("compat/folia")
