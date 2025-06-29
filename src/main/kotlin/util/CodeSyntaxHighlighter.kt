package util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

fun syntaxHighlight(code: String, language: String = "typescript"): AnnotatedString {
    val keywords = when (language) {
        "typescript", "javascript" -> listOf(
            "abstract", "class", "constructor", "export", "extends", "function", "import", "interface", "implements",
            "new", "public", "private", "protected", "return", "super", "this", "void"
        )

        "gherkin" -> listOf("Feature", "Scenario", "Given", "When", "Then", "And", "But", "Examples")
        else -> emptyList()
    }

    val builder = buildAnnotatedString {
        append(code)

        // Highlight keywords
        keywords.forEach { keyword ->
            var index = code.indexOf(keyword)
            while (index >= 0) {
                addStyle(
                    style = SpanStyle(color = Color(0xFF569CD6)),
                    start = index,
                    end = index + keyword.length
                )
                index = code.indexOf(keyword, startIndex = index + keyword.length)
            }
        }

        // Highlight strings
        val stringRegex = Regex("\".*?\"|'.*?'")
        stringRegex.findAll(code).forEach { matchResult ->
            addStyle(
                style = SpanStyle(color = Color(0xFFCE9178)),
                start = matchResult.range.first,
                end = matchResult.range.last + 1
            )
        }

        // Highlight comments for TypeScript or JavaScript
        if (language == "typescript" || language == "javascript") {
            val commentRegex = Regex("//.*?$|/\\*.*?\\*/", RegexOption.MULTILINE)
            commentRegex.findAll(code).forEach { matchResult ->
                addStyle(
                    style = SpanStyle(color = Color(0xFF6A9955)), // Green comment color
                    start = matchResult.range.first,
                    end = matchResult.range.last + 1
                )
            }
        }
    }

    return builder
}
