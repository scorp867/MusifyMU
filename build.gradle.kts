plugins {
    id("com.android.application") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("com.google.devtools.ksp") version "2.0.20-1.0.24" apply false
}

// Gradle will use the launcher JVM (Java 21) unless a toolchain is configured in modules.
// The Android plugin will provision JDK 17 internally when needed; no root java block required.

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}
