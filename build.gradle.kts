plugins {
    id("com.android.application") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" apply false
    id("com.google.dagger.hilt.android") version "2.48" apply false
}

// Gradle will use the launcher JVM (Java 21) unless a toolchain is configured in modules.
// The Android plugin will provision JDK 17 internally when needed; no root java block required.

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}
