plugins { base }

allprojects {
    group = "dev.worldmirror"
    version = "0.1.1"
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing", "-Werror"))
    }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).encoding = "UTF-8"
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }
}

val lintJava by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks Java source length and control-flow nesting. Run npm run format first."
    mainClass.set("scripts/CheckJavaLimits.java")
    args(listOf("scripts") + project.subprojects.map { "${it.name}/src" })
}
tasks.check { dependsOn(lintJava) }
