package agent.semantic

import agent.candidates.UICandidate

class SemanticReranker(private val embedder: Embedder) {
    private fun cos(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0;
        var na = 0.0;
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
        }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }

    fun rerank(hint: String, cands: List<UICandidate>): List<UICandidate> {
        if (cands.isEmpty()) return cands
        val q = embedder.embed(hint)
        val scored = cands.map { c ->
            val text = listOfNotNull(c.label, c.role, c.containerId).joinToString(" ")
            val s = cos(q, embedder.embed(text))
            // small priors for structure we know helps
            val prior = when ((c.role ?: "").lowercase()) {
                "button" -> 0.06
                "bottom_nav", "nav", "tab" -> 0.04
                else -> 0.0
            } + if (c.clickable) 0.02 else 0.0
            s + prior
        }.zip(cands).sortedByDescending { it.first }.map { it.second }
        return scored
    }
}