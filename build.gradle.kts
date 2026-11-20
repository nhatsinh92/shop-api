// Root build: shared Java toolchain, formatting, static analysis and coverage.
// This file is lint/coverage configuration and is a protected path (see CLAUDE.md).
plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.spotless)
}

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

allprojects {
    group = "com.mayshop"
    version = "0.1.0-SNAPSHOT"
}

spotless {
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktlint()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "checkstyle")
    apply(plugin = "jacoco")
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    // Lock every resolved configuration so builds are reproducible and the
    // dependency audit can scan gradle.lockfile. Refresh with `make lock`.
    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.register("resolveAndLockAll") {
        description = "Resolves every configuration; run with --write-locks to refresh gradle.lockfile."
        notCompatibleWithConfigurationCache("resolves configurations at execution time")
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) { "Run with --write-locks" }
        }
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat()
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    extensions.configure<CheckstyleExtension> {
        toolVersion = libsCatalog.findVersion("checkstyle").get().requiredVersion
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        isIgnoreFailures = false
        maxWarnings = 0
    }

    extensions.configure<JacocoPluginExtension> {
        toolVersion = libsCatalog.findVersion("jacoco").get().requiredVersion
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        finalizedBy("jacocoTestReport")
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        reports {
            xml.required = true
            html.required = true
        }
    }
}
