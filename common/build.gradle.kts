dependencies {
    // The scheduling seam references Bukkit Plugin/Location/Entity; the pure
    // physics classes use no Bukkit types. The server supplies the API at
    // runtime, so it stays compileOnly.
    compileOnly(libs.paper.api.floor)
    compileOnly(libs.jetbrains.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.paper.api.floor)
}
