plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core-format"))
}

application {
    mainClass.set("photos.engram.cli.MainKt")
}
