package agent.util

data class GenericRule(val strategy: String, val regex: Regex)

/** Returns true when a selector is too generic/brittle to be saved or reused. */
private val GENERIC_RULES = listOf(
    GenericRule("XPATH", Regex("""\(\.//\*\[@clickable=['"]true['"]\]\)\[1\]""", RegexOption.IGNORE_CASE)),
    GenericRule("XPATH", Regex("""^//\*$""")),
    GenericRule("XPATH", Regex("""\[@resource-id=['"]null['"]\]""", RegexOption.IGNORE_CASE)),
    GenericRule("XPATH", Regex("""^\(\.//\*\)\[1\]$""", RegexOption.IGNORE_CASE)),
    GenericRule("ID", Regex("""^null$""", RegexOption.IGNORE_CASE)),
    GenericRule("DESC", Regex("""^\s*$""")) // empty content-desc
)

fun isGenericSelector(strategy: String, value: String): Boolean {
    val s = strategy.uppercase()
    val v = value.trim()
    return GENERIC_RULES.any { it.strategy.equals(s, true) && it.regex.containsMatchIn(v) }
}
