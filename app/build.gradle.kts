plugins {
    // AGP 9 built-in Kotlin: no standalone kotlin-android plugin; the compose
    // compiler plugin is still required and must match the compiler in use
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
}

android {
    namespace = "cam.engram.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "cam.engram.app"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
        // instrumented confidence layer (D22): a build-managed emulator covers the real platform
        // adapters Kover excludes. Run with `./gradlew pixelApi34DebugAndroidTest` locally or in CI.
        managedDevices {
            localDevices {
                create("pixelApi34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }

    lint {
        // every shipped locale must stay complete; a missing translation fails the build
        error += "MissingTranslation"
    }
}

dependencies {
    implementation(project(":core-format"))
    // explicit coroutines at the catalog version so the app packages the same version the
    // (androidTest) coroutines-test compiles against; transitive resolution otherwise lags
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.exifinterface)
    implementation(libs.documentfile)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.work.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(kotlin("test"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom(
        "src/main/kotlin",
        "src/test/kotlin",
    )
}

kover {
    reports {
        filters {
            excludes {
                // generated code (Room DAOs/db, Compose singletons, build metadata) and
                // process entry points that no unit test drives
                classes(
                    "*.ComposableSingletons*",
                    "*.BuildConfig",
                    "cam.engram.app.data.db.*_Impl*",
                    "cam.engram.app.MainActivity*",
                    "cam.engram.app.EngramApp*",
                )
                // Thin platform-adapter edges: real MediaStore/ContentResolver, SAF, the
                // SpeechRecognizer and MediaRecorder wrappers, Geocoder, the media-observer
                // service. The logic behind each interface is unit-tested through fakes; the
                // adapters themselves only run on a device and are covered by the instrumented
                // androidTest layer, which Kover cannot measure on-device. LabScreen is debug
                // diagnostics (exempt like the lab strings).
                classes(
                    "cam.engram.app.data.media.ResolverContentAccess",
                    "cam.engram.app.data.media.MediaStoreSource",
                    "cam.engram.app.audio.MediaVoiceRecorder",
                    "cam.engram.app.ui.SpeechInputKt*",
                    "cam.engram.app.export.ArchiveExporter*",
                    "cam.engram.app.enrich.GeocoderPlaceProvider*",
                    "cam.engram.app.enrich.OpenMeteoWeatherProvider*",
                    "cam.engram.app.work.MediaObserverService*",
                    "cam.engram.app.ui.LabScreenKt*",
                    // SAF export/verify result callbacks: fire only on a real document-picker result
                    "*exportLauncher*",
                    "*verifyLauncher*",
                )
                // device-only code (MediaPlayer playback, SpeechRecognizer dictation) that cannot
                // run on the JVM; the instrumented layer covers it (design D22)
                annotatedBy("cam.engram.app.DeviceOnly")
            }
        }
        // floor set below the achieved ~91% with headroom for Compose-timing coverage variance;
        // the residual to 95% is device-only Compose (MediaPlayer/SpeechRecognizer) covered by the
        // instrumented layer (design D22). Floor only rises.
        verify { rule { minBound(88) } }
    }
}
