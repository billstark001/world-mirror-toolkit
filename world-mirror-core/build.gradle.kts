plugins { `java-library` }

dependencies {
    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
}

tasks.test { useJUnitPlatform() }
