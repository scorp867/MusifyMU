pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.maxhenkel.de/repository/public") }
        maven { url = uri("https://jcenter.bintray.com") }
    }
    plugins {
        id("com.android.application") version "8.6.0"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.ksp") version "2.0.21"
        id("com.google.dagger.hilt.android") version "2.48"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.maxhenkel.de/repository/public") }
        maven { url = uri("https://jcenter.bintray.com") }
    }
}

// Enable toolchain auto-provisioning repositories for JDK download
@OptIn(org.gradle.toolchains.foojay.FoojayToolchainsResolverPlugin::class)

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "MusifyMU"
include(":app")
