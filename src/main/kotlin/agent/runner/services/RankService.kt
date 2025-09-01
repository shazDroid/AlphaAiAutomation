package agent.runner.services

import agent.candidates.UICandidate
import agent.runner.RunContext
import kotlin.math.abs

/**
 * Candidate ranking and scoping utilities.
 */
class RankService(
    private val ctx: RunContext,
    private val xpaths: XPathService,
    private val vision: VisionService
) {
    private data class Ranked(val cand: UICandidate, val score: Int)

    fun rank(hint: String, all: List<UICandidate>): List<UICandidate> {
        if (all.isEmpty()) return all
        val ranked = all.map { c ->
            val lbl = c.label?.takeIf { it.isNotBlank() } ?: labelForXpath(c.xpath)
            Ranked(c, scoreByText(hint, lbl))
        }.filter { it.score >= 40 }.sortedByDescending { it.score }
        return ranked.map { it.cand }
    }

    fun scopeXY(all: List<UICandidate>, preferred: String?, v: agent.vision.VisionResult?): List<UICandidate> {
        if (preferred == null) return all
        val header = when (preferred.lowercase()) {
            "from" -> headerBox(v, "from"); "to" -> headerBox(v, "to"); else -> null
        }
        return if (header == null) all else all.sortedBy {
            val r =
                runCatching { ctx.driver.findElement(io.appium.java_client.AppiumBy.xpath(it.xpath)).rect }.getOrNull()
            if (r == null) Int.MAX_VALUE else abs((r.y + r.height / 2) - header.second) + abs((r.x + r.width / 2) - header.first) / 2
        }
    }

    private fun headerBox(v: agent.vision.VisionResult?, token: String): Pair<Int, Int>? {
        val e = v?.elements?.firstOrNull { (it.text ?: "").equals(token, true) } ?: return null
        val cx = (e.x ?: 0) + (e.w ?: 0) / 2
        val cy = (e.y ?: 0) + (e.h ?: 0) / 2
        return cx to cy
    }

    private fun labelForXpath(xp: String): String {
        return runCatching {
            val el = ctx.driver.findElements(io.appium.java_client.AppiumBy.xpath(xp)).firstOrNull() ?: return ""
            val t = (runCatching { el.text }.getOrNull() ?: "").trim()
            val d = el.getAttribute("content-desc")?.trim().orEmpty()
            val rid = el.getAttribute("resource-id")?.substringAfterLast('/')?.trim().orEmpty()
            listOf(t, d, rid).firstOrNull { it.isNotBlank() } ?: ""
        }.getOrDefault("")
    }

    private fun normTokens(s: String?): List<String> =
        (s ?: "").lowercase().split(Regex("\\W+")).filter { it.length >= 2 }

    private fun scoreByText(hint: String, label: String): Int {
        val H = hint.trim().lowercase()
        val L = label.trim().lowercase()
        if (L == H) return 100
        if (L.contains(H)) return 85
        val ht = normTokens(H).toSet()
        val lt = normTokens(L).toSet()
        val inter = ht.intersect(lt).size
        return when {
            inter == ht.size && ht.isNotEmpty() -> 75
            inter > 0 -> 45 + inter * 5
            else -> 0
        }
    }
}
