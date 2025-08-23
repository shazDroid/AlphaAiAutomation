// util/UiHintsBuilder.kt
package util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

object UiHelper {
    fun colorForLog(line: String): Color = when {
        "❌" in line || "FAIL" in line || "error" in line.lowercase() -> Color(0xFFD32F2F) // red
        "✓" in line || " OK" in line || "Done" in line -> Color(0xFF2E7D32)              // green
        "retry(" in line || "retry " in line -> Color(0xFFF9A825)                         // amber
        "WAIT" in line || "scroll" in line.lowercase() -> Color(0xFF1565C0)               // blue
        else -> Color(0xffffffff)
    }

    fun styleLogLine(line: String): AnnotatedString {
        val i = line.indexOf(']')
        return buildAnnotatedString {
            if (i > 0) {
                // time
                append(AnnotatedString(line.substring(0, i + 1), SpanStyle(color = Color.Gray)))
                append(" ")
                // message
                append(AnnotatedString(line.substring(i + 1).trimStart(), SpanStyle(color = colorForLog(line))))
            } else {
                append(AnnotatedString(line, SpanStyle(color = colorForLog(line))))
            }
        }
    }
}
