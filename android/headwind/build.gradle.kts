// Deliberately a plain Kotlin JVM library, not an Android module.
//
// Nothing in here touches the Android framework or karoo-ext, which means its
// tests run on a normal JVM in about a second — no emulator, no SDK, and no
// GitHub Packages credentials needed to execute them.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}
