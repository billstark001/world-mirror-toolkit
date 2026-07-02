plugins { application }

dependencies {
    implementation(project(":world-mirror-core"))
    implementation(project(":world-mirror-schema"))
    implementation(project(":world-mirror-replay"))
    implementation(project(":world-mirror-protocol"))
    implementation(project(":world-mirror-anvil"))
}

application {
    mainClass.set("dev.worldmirror.toolkit.gui.WorldMirrorToolkitGui")
}
