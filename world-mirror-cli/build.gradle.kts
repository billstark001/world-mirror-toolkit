plugins { application }

dependencies {
    implementation(project(":world-mirror-core"))
    implementation(project(":world-mirror-schema"))
    implementation(project(":world-mirror-replay"))
    implementation(project(":world-mirror-protocol"))
    implementation(project(":world-mirror-anvil"))
    implementation(libs.picocli)
    annotationProcessor(libs.picocliCodegen)
    implementation(libs.jacksonDatabind)
    runtimeOnly(libs.slf4jSimple)
}

application {
    mainClass.set("dev.worldmirror.toolkit.cli.WorldMirrorCli")
}

tasks.jar {
    manifest { attributes("Main-Class" to application.mainClass.get()) }
}
