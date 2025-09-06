package agent.semantic

import kotlin.math.sqrt

object SemanticMatcher {
    fun score(a: String, b: String): Double {
        val ta = normTokens(a)
        val tb = normTokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val vocab = (ta.keys + tb.keys).toSet()
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (t in vocab) {
            val xa = ta[t]?.toDouble() ?: 0.0
            val xb = tb[t]?.toDouble() ?: 0.0
            dot += xa * xb
            na += xa * xa
            nb += xb * xb
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0.0) 0.0 else dot / denom
    }

    private fun normTokens(s: String): Map<String, Int> {
        val cleaned = s.lowercase()
            .replace(Regex("[^a-z0-9\\s]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) return emptyMap()
        val toks = cleaned.split(" ")
            .filter { it.length >= 2 }
            .map { stem(it) }
        val map = HashMap<String, Int>()
        toks.forEach { t -> map[t] = (map[t] ?: 0) + 1 }
        return map
    }

    private fun stem(t: String): String {
        return when {
            t.endsWith("ing") && t.length > 5 -> t.dropLast(3)
            t.endsWith("ed") && t.length > 4 -> t.dropLast(2)
            t.endsWith("es") && t.length > 4 -> t.dropLast(2)
            t.endsWith("s") && t.length > 3 -> t.dropLast(1)
            else -> t
        }
    }
}
