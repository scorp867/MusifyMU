plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.musify.mu"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.musify.mu"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildFeatures {
        compose = true
    }

    // composeOptions no longer required when using Kotlin 2.0 compose plugin

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packagingOptions {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
            excludes += "NATIVE_UTILS_LICENSE"
        }
    }
}

// Ensure toolchain uses JDK 17 for all Kotlin tasks
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.7.3")

    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("com.google.android.material:material:1.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("org.burnoutcrew.composereorderable:reorderable:0.9.6")

    // Coil for lazy image loading with disk caching
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("io.coil-kt:coil:2.5.0")
    implementation("io.coil-kt:coil-gif:2.5.0")
    
    // Glide as alternative for non-Compose usage
    implementation("com.github.bumptech.glide:glide:4.16.0")

    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    implementation("androidx.palette:palette-ktx:1.0.0")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media:media:1.7.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation("androidx.lifecycle:lifecycle-process:2.8.3")

    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Unit tests
    testImplementation("junit:junit:4.13.2")

    // Instrumented Android tests
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    // Google Fonts for Compose (downloadable fonts)
    implementation("androidx.compose.ui:ui-text-google-fonts:1.6.8")
    // Speech Module For Voice Commands (GYM MODE)
    implementation("com.alphacephei:vosk-android:0.3.47")
    // Picovoice Porcupine for wake word detection

    // RNNoise for real-time audio noise suppression

    // WebRTC Android (Threema prebuilt) for access to org.webrtc audio processing
    implementation("ch.threema:webrtc-android:100.0.0")


}

// Room KSP options
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
}
