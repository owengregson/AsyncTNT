dependencies {
    // Compiled against the Folia API floor (1.20.4) so it can name the region
    // schedulers directly; its classes are bundled into core's shaded jar and
    // only linked at runtime when the Folia capability is present.
    compileOnly(project(":common"))
    compileOnly(libs.paper.api.folia)
    compileOnly(libs.jetbrains.annotations)
}
