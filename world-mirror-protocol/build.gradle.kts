plugins { `java-library` }

dependencies {
    api(project(":world-mirror-core"))
    api(project(":world-mirror-schema"))
    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
}

tasks.test { useJUnitPlatform() }
