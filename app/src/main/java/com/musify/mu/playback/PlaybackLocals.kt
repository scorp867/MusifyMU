package com.musify.mu.playback

import androidx.compose.runtime.staticCompositionLocalOf

val LocalPlaybackMediaId = staticCompositionLocalOf<String?> { null }
val LocalIsPlaying = staticCompositionLocalOf<Boolean> { false }


