plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(21)
    jvm()
    // portability tripwire (design doc sec 10): commonMain must stay compilable
    // for iOS even though no app consumes it yet
    iosArm64()
    sourceSets {
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.xmpcore)
            }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom(
        "src/commonMain/kotlin",
        "src/commonTest/kotlin",
        "src/jvmMain/kotlin",
        "src/jvmTest/kotlin",
    )
}

kover {
    reports {
        filters {
            excludes {
                // synthetic test-media builders shipped in commonMain, not product code
                packages("cam.engram.format.testing")
            }
        }
        // line-coverage floor; raise toward 100 as coverage climbs, never lower (see AGENTS.md)
        verify { rule { minBound(95) } }
    }
}
