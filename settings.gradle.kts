pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "world-mirror-toolkit"

include(
    "world-mirror-core",
    "world-mirror-schema",
    "world-mirror-replay",
    "world-mirror-protocol",
    "world-mirror-anvil",
    "world-mirror-cli",
    "world-mirror-gui"
)
