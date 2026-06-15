import xyz.jpenilla.runpaper.task.RunServer

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

evaluationDependsOn(":tester")

dependencies {
    api(project(":api"))
    api(project(":common"))
    compileOnly(libs.paper.api.floor)
    compileOnly(libs.jetbrains.annotations)
    // The reflective NMS bridge routes Mojang names through reflection-remapper
    // (reobf on <=1.20.4 servers, no-op on Mojang-mapped 1.20.5+).
    implementation(libs.reflection.remapper)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.paper.api.floor)
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    // compat-folia is compiled against a newer API; bundle its raw class output
    // (never compiled against here) so it rides along but only links at runtime
    // when the Folia capability is present.
    dependsOn(":compat-folia:classes")
    archiveBaseName.set("AsyncTNT")
    archiveClassifier.set("")

    from(project(":compat-folia").sourceSets.main.get().output)

    relocate("xyz.jpenilla.reflectionremapper", "me.vexmc.asynctnt.lib.reflectionremapper")
    relocate("net.fabricmc.mappingio", "me.vexmc.asynctnt.lib.mappingio")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

/* ────────────────────────────────────────────────────────────────────────
 *  Real-server integration matrix
 *
 *  For every version in gradle.properties' integrationTestVersions, a real
 *  Paper server boots with the AsyncTNT and AsyncTNTTester jars installed; the
 *  tester runs its suite in-process, writes PASS/FAIL, and shuts the server
 *  down; the paired check task fails the build on anything but PASS.
 * ──────────────────────────────────────────────────────────────────────── */

val integrationTestVersions: List<String> =
    (findProperty("integrationTestVersions") as String?)
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: listOf("1.17.1", "26.1.2")

fun parseMinecraftVersion(version: String): Triple<Int, Int, Int> {
    val parts = version.split(".")
    return Triple(
        parts.getOrNull(0)?.toIntOrNull() ?: 0,
        parts.getOrNull(1)?.toIntOrNull() ?: 0,
        parts.getOrNull(2)?.toIntOrNull() ?: 0,
    )
}

// 1.17–1.20.4 class files target Java 17; 1.20.5+ requires 21 and runs
// happily on 25 — two toolchains cover the whole matrix.
fun requiredJavaVersion(version: String): Int {
    val (major, minor, patch) = parseMinecraftVersion(version)
    if (major > 1 || minor > 20 || (minor == 20 && patch >= 5)) return 25
    return 17
}

val javaToolchains = extensions.getByType<JavaToolchainService>()
val testerShadowJar = project(":tester").tasks.named<AbstractArchiveTask>("shadowJar")

tasks.named<RunServer>("runServer") {
    enabled = false
    description = "Disabled — use integrationTest or integrationTestMatrix."
}

val checkTasks = mutableListOf<TaskProvider<Task>>()
var previousCheck: TaskProvider<Task>? = null

/**
 * One live-server suite: boots Paper [version] in run/[version] with AsyncTNT
 * and the tester; the paired check task fails the build unless the tester wrote
 * PASS. Run tasks are chained sequentially — every server binds the same port.
 */
fun registerIntegrationServer(
    taskSuffix: String,
    version: String,
    runDirName: String,
): Pair<TaskProvider<RunServer>, TaskProvider<Task>> {
    val runDir = rootProject.layout.projectDirectory.dir("run/$runDirName").asFile
    val resultFile = runDir.resolve("plugins/AsyncTNTTester/test-results.txt")
    val failuresFile = runDir.resolve("plugins/AsyncTNTTester/test-failures.txt")
    val logFile = layout.buildDirectory.file("integration-test-logs/${runDirName.replace('/', '-')}.log")

    val runTask = tasks.register<RunServer>("runIntegrationTest$taskSuffix") {
        group = "asynctnt integration"
        description = "Boots Paper $version with AsyncTNT + tester and runs the suite."
        dependsOn(tasks.shadowJar, testerShadowJar)
        runDirectory.set(runDir)
        minecraftVersion(version)
        // disable.watchdog matters on slow CI runners: a >60s tick stall trips
        // the legacy watchdog, whose forced shutdown can deadlock old servers
        // into a hung process that never writes a test result.
        jvmArgs("-Dcom.mojang.eula.agree=true", "-Ddisable.watchdog=true", "-Xmx2G")
        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(requiredJavaVersion(version)))
        })
        pluginJars.from(tasks.shadowJar.flatMap { it.archiveFile })
        pluginJars.from(testerShadowJar.flatMap { it.archiveFile })

        doFirst {
            resultFile.delete()
            failuresFile.delete()
            // The config tree resets per run so every suite starts from defaults.
            runDir.resolve("plugins/AsyncTNT").deleteRecursively()
            val properties = runDir.resolve("server.properties")
            if (!properties.exists()) {
                runDir.mkdirs()
                properties.writeText(
                    """
                    level-type=flat
                    online-mode=false
                    spawn-protection=0
                    view-distance=4
                    simulation-distance=4
                    motd=AsyncTNT integration test
                    """.trimIndent() + "\n"
                )
            }
            val log = logFile.get().asFile
            log.parentFile.mkdirs()
            val stream = log.outputStream()
            standardOutput = stream
            errorOutput = stream
        }
        doLast {
            (standardOutput as? java.io.Closeable)?.close()
        }
    }

    val checkTask = tasks.register("checkIntegrationTest$taskSuffix") {
        group = "asynctnt integration"
        description = "Verifies the $version suite reported PASS."
        dependsOn(runTask)
        doLast {
            val log = logFile.get().asFile
            if (!resultFile.exists()) {
                throw GradleException(
                    "No test result for $version — server crashed or hung. Log: ${log.absolutePath}")
            }
            if (failuresFile.exists()) {
                failuresFile.readLines().filter { it.isNotBlank() }.take(10).forEach {
                    logger.lifecycle("[$version] FAILURE: $it")
                }
            }
            when (val result = resultFile.readText().trim()) {
                "PASS" -> logger.lifecycle("[$version] integration tests passed. Log: ${log.absolutePath}")
                "FAIL" -> throw GradleException("Integration tests failed for $version. Log: ${log.absolutePath}")
                else -> throw GradleException("Unknown test result '$result' for $version.")
            }
        }
    }
    return runTask to checkTask
}

integrationTestVersions.forEach { version ->
    val suffix = "_" + version.replace(".", "_")
    val (runTask, checkTask) = registerIntegrationServer(suffix, version, version)
    previousCheck?.let { prior -> runTask.configure { mustRunAfter(prior) } }
    previousCheck = checkTask
    checkTasks.add(checkTask)
}

tasks.register("integrationTest") {
    group = "asynctnt integration"
    description = "Runs the suite on the floor and newest supported versions."
    val floorAndCeiling = setOf(integrationTestVersions.first(), integrationTestVersions.last())
    dependsOn(checkTasks.filter { provider ->
        floorAndCeiling.any { provider.name.endsWith("_" + it.replace(".", "_")) }
    })
}

tasks.register("integrationTestMatrix") {
    group = "asynctnt integration"
    description = "Runs the suite on every version in integrationTestVersions."
    dependsOn(checkTasks)
}
