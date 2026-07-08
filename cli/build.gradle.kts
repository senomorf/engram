plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
    `jvm-test-suite`
}

kotlin {
    jvmToolchain(21)
    // integrationTest is its own compilation; associate it so internals are visible
    target.compilations.matching { it.name == "integrationTest" }.configureEach {
        associateWith(target.compilations.getByName("main"))
    }
}

dependencies {
    implementation(project(":core-format"))
}

application {
    mainClass.set("cam.engram.cli.MainKt")
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useKotlinTest(libs.versions.kotlin)
        }
        register<JvmTestSuite>("integrationTest") {
            useKotlinTest(libs.versions.kotlin)
            dependencies {
                implementation(project())
                implementation(project(":core-format"))
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"))
}

kover {
    reports {
        // coverage comes entirely from the integrationTest suite (there is no unit test suite)
        verify { rule { minBound(90) } }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom(
        "src/main/kotlin",
        "src/test/kotlin",
        "src/integrationTest/kotlin",
    )
}
