package com.musify.mu.lyrics

data class LrcLine(val timeMs: Long, val text: String)

object LrcParser {
    // Enhanced regex to capture various timestamp formats:
    // [mm:ss.xx], [mm:ss], [mm:ss.xxx], [h:mm:ss.xx], etc.
    private val timeRegex = Regex("""\[(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(text: String): List<LrcLine> {
        val lines = mutableListOf<LrcLine>()
        
        text.lineSequence().forEach { raw ->
            val trimmedRaw = raw.trim()
            if (trimmedRaw.isEmpty()) return@forEach // Skip empty lines
            
            val matches = timeRegex.findAll(trimmedRaw).toList()
            
            if (matches.isNotEmpty()) {
                // Extract lyric text by removing all timestamps
                val lyric = trimmedRaw.replace(timeRegex, "").trim()
                
                // Process each timestamp found on this line
                matches.forEach { match ->
                    val hours = match.groupValues[1].toLongOrNull() ?: 0L
                    val minutes = match.groupValues[2].toLong()
                    val seconds = match.groupValues[3].toLong()
                    val fraction = match.groupValues[4].takeIf { it.isNotEmpty() }?.let { frac ->
                        // Handle different decimal precision (2-digit centiseconds, 3-digit milliseconds)
                        when (frac.length) {
                            1 -> frac.toLong() * 100 // .X -> X00 ms
                            2 -> frac.toLong() * 10  // .XX -> XX0 ms  
                            3 -> frac.toLong()       // .XXX -> XXX ms
                            else -> frac.take(3).toLong() // Truncate if more than 3 digits
                        }
                    } ?: 0L
                    
                    val totalMs = hours * 3600_000 + minutes * 60_000 + seconds * 1_000 + fraction
                    
                    // Always add the line, even if lyric is empty (for instrumental breaks, etc.)
                    lines += LrcLine(totalMs, lyric)
                }
            } else {
                // Line has no timestamps - could be metadata or lyrics without timing
                // Check if it looks like LRC metadata (starts with [, but no time pattern)
                if (trimmedRaw.startsWith("[") && !trimmedRaw.matches(Regex("""\[.*\d+.*].*"""))) {
                    // Skip metadata lines like [ar:Artist], [ti:Title], etc.
                    return@forEach
                }
                
                // For lines without timestamps, add them as plain text at time 0
                // This ensures no lyrics are lost even in malformed LRC files
                if (trimmedRaw.isNotEmpty()) {
                    lines += LrcLine(0L, trimmedRaw)
                }
            }
        }
        
        return lines.sortedBy { it.timeMs }.distinctBy { "${it.timeMs}_${it.text}" }
    }
    
    /**
     * Check if text contains LRC-style timestamps
     */
    fun isLrcFormat(text: String): Boolean {
        if (text.isBlank()) return false
        
        // Count lines with timestamps vs total non-empty lines
        val nonEmptyLines = text.lines().filter { it.trim().isNotEmpty() }
        if (nonEmptyLines.isEmpty()) return false
        
        val linesWithTimestamps = nonEmptyLines.count { line ->
            timeRegex.containsMatchIn(line.trim())
        }
        
        // Consider it LRC if at least 30% of non-empty lines have timestamps
        // or if there are at least 3 timestamped lines
        return linesWithTimestamps >= 3 || 
               (linesWithTimestamps.toDouble() / nonEmptyLines.size) >= 0.3
    }
}
