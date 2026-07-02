plugins { `java-library` }

dependencies {
    api(project(":world-mirror-core"))
    api(project(":world-mirror-protocol"))
    implementation(libs.ensNbt)
    implementation(libs.ensNbtMca)
    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
}

tasks.test { useJUnitPlatform() }
