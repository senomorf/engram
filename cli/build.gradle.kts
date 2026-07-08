plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
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

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom(
        "src/main/kotlin",
        "src/test/kotlin",
        "src/integrationTest/kotlin",
    )
}
