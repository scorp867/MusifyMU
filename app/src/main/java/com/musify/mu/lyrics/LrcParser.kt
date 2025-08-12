package com.musify.mu.lyrics

data class LrcLine(val timeMs: Long, val text: String)

object LrcParser {
    // Regex to capture [mm:ss.xx] or [mm:ss] timestamps
    private val timeRegex = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,2}))?]""")

    fun parse(text: String): List<LrcLine> {
        val lines = mutableListOf<LrcLine>()
        text.lineSequence().forEach { raw ->
            val matches = timeRegex.findAll(raw).toList()
            val lyric = raw.replace(timeRegex, "").trim()
            matches.forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val cs = m.groupValues.getOrNull(3)?.toLongOrNull() ?: 0L
                val ms = min * 60_000 + sec * 1_000 + cs * 10
                lines += LrcLine(ms, lyric)
            }
        }
        return lines.sortedBy { it.timeMs }
    }
}
