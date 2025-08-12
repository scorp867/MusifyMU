# Keep class names and methods for Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep Jetpack Compose runtime classes (to prevent issues with reflection)
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep Room entity, DAO, and database classes
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**
-keep class com.musify.mu.data.db.** { *; }

# Keep DataStore classes
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# Keep SpeechRecognizer related classes
-keep class android.speech.** { *; }

# General Kotlin metadata
-keepclassmembers class kotlin.Metadata { *; }

# Prevent obfuscating model/data classes
-keepclassmembers class com.musify.mu.data.db.entities.** { *; }
