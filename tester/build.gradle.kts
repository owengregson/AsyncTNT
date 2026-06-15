plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":common"))
    compileOnly(project(":core"))
    compileOnly(libs.paper.api.floor)
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.netty.all)
    implementation(libs.reflection.remapper)
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveBaseName.set("AsyncTNTTester")
    archiveClassifier.set("")

    relocate("xyz.jpenilla.reflectionremapper", "me.vexmc.asynctnt.tester.lib.reflectionremapper")
    relocate("net.fabricmc.mappingio", "me.vexmc.asynctnt.tester.lib.mappingio")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
