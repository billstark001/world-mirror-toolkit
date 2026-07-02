plugins { `java-library` }

dependencies {
    api(project(":world-mirror-core"))
    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
}

tasks.test { useJUnitPlatform() }
