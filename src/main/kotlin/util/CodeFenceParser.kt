package util

/**
 * Parses a response containing multiple fenced code blocks named with filenames, e.g.:
 * ```BasePage.ts
 * // content
 * ```
 * Returns a map of "BasePage.ts" -> "content".
 */
object CodeFenceParser {
    fun split(text: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("```")) {
                val fenceHeader = line.removePrefix("```").trim()
                // We expect the header to be the filename like BasePage.ts
                val fileName = fenceHeader.ifBlank { "unknown_${out.size}.txt" }
                val buf = StringBuilder()
                i += 1
                while (i < lines.size && !lines[i].startsWith("```")) {
                    buf.appendLine(lines[i])
                    i += 1
                }
                out[fileName] = buf.toString().trimEnd()
            }
            i += 1
        }
        return out
    }
}
