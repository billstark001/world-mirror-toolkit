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
        ivy {
            name = "worldMirrorFormatReleases"
            url = uri("https://github.com/billstark001/world-mirror/releases/download")
            patternLayout {
                artifact("world-mirror-format-v[revision]/[artifact]-[revision](-[classifier]).[ext]")
            }
            metadataSources { artifact() }
            content { includeGroup("io.github.billstark001.worldmirror") }
        }
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
