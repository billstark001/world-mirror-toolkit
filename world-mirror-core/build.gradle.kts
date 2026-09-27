plugins { `java-library` }

dependencies {
    api("io.github.billstark001.worldmirror:world-mirror-format:0.1.1")
    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
}

tasks.test { useJUnitPlatform() }
