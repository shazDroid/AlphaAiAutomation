package util

fun extractCodeBetweenMarkers(text: String, startMarker: String, endMarker: String): String {
    val regex = Regex(
        "(?s)#+\\s*$startMarker.*?```[a-zA-Z]*\\n(.*?)```.*?#+\\s*$endMarker"
    )
    val match = regex.find(text)
    return match?.groups?.get(1)?.value?.trim() ?: ""
}



