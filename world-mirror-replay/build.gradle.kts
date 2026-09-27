plugins { `java-library` }

dependencies {
    api(project(":world-mirror-core"))
    implementation(libs.jacksonDatabind)
    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
}

tasks.test { useJUnitPlatform() }
