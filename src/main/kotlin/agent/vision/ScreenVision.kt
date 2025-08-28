package agent.vision

import agent.Locator
import agent.Strategy
import io.appium.java_client.AppiumBy
import io.appium.java_client.android.AndroidDriver
import org.openqa.selenium.WebElement
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Helpers that blend vision detections with Appium DOM:
 * - narrow TAP candidates to a "FROM"/"TO" section
 * - find the switch/checkbox nearest to a label detected by vision
 */
object ScreenVision {

    /**
     * Keeps only those Appium candidates that visually land in the requested section,
     * using "from" / "to" text y-anchors detected by vision.
     */
    fun restrictCandidatesToSection(
        driver: AndroidDriver,
        candidates: List<agent.candidates.UICandidate>,
        vres: VisionResult,
        section: String
    ): List<agent.candidates.UICandidate> {
        val fromY = vres.elements.firstOrNull { (it.text ?: "").equals("from", true) }?.y
        val toY = vres.elements.firstOrNull { (it.text ?: "").equals("to", true) }?.y
        if (fromY == null && toY == null) return candidates

        fun inFrom(y: Int): Boolean {
            if (fromY == null && toY == null) return true
            if (fromY != null && toY != null) return y in (min(fromY, toY) - 40)..(max(fromY, toY) - 40)
            if (fromY != null) return y >= fromY - 40
            return y >= (toY ?: 0) - 40
        }

        fun inTo(y: Int): Boolean = if (toY != null) y >= toY - 40 else true

        return candidates.filter { c ->
            val rect = runCatching { driver.findElement(AppiumBy.xpath(c.xpath)).rect }.getOrNull()
            if (rect == null) true else {
                when (section.lowercase()) {
                    "from" -> inFrom(rect.y)
                    "to" -> inTo(rect.y)
                    else -> true
                }
            }
        }
    }

    /**
     * Given a label string and vision result, find the nearest visible checkable UI (Switch/Checkbox)
     * on the same row (by y-center proximity). Returns (Locator, WebElement) if found.
     */
    fun findToggleForLabel(
        driver: AndroidDriver,
        vres: VisionResult,
        label: String,
        section: String?
    ): Pair<Locator, WebElement>? {
        val lab = norm(label)

        // Best matching vision text element for the given label
        val labelElem = vres.elements
            .filter { !it.text.isNullOrBlank() }
            .maxByOrNull { scoreLabel(lab, it.text!!) }
            ?: return null

        // If the prompt said "FROM"/"TO", bias the chosen label by section anchors
        val fromY = vres.elements.firstOrNull { (it.text ?: "").equals("from", true) }?.y
        val toY = vres.elements.firstOrNull { (it.text ?: "").equals("to", true) }?.y
        val labelYCenter = labelElem.y + labelElem.h / 2
        val inFrom = section?.equals("from", true) == true && fromY != null &&
                labelYCenter in (min(fromY, toY ?: fromY) - 80)..(max(fromY, toY ?: fromY) + 80)
        val inTo = section?.equals("to", true) == true && toY != null &&
                labelYCenter >= toY - 80

        // Find nearest checkable element along the same row
        val checkables = driver.findElements(
            AppiumBy.xpath("//*[self::android.widget.Switch or @checkable='true']")
        )
        if (checkables.isEmpty()) return null

        val best = checkables
            .map { el ->
                val yCenter = runCatching { el.rect.y + el.rect.height / 2 }.getOrNull() ?: 0
                val xRight = runCatching { el.rect.x + el.rect.width }.getOrNull() ?: 0
                Triple(el, abs(yCenter - labelYCenter), xRight)
            }
            // sort by y-distance, then prefer the rightmost element (common for toggles)
            .sortedWith(compareBy<Triple<WebElement, Int, Int>>({ it.second }).thenByDescending { it.third })
            .map { it.first }
            .firstOrNull()
            ?: return null

        // If section was specified and the chosen element violates the section, bail out
        if (section != null) {
            val y = runCatching { best.rect.y + best.rect.height / 2 }.getOrNull() ?: 0
            val ok = when (section.lowercase()) {
                "from" -> fromY == null || y >= fromY - 80
                "to" -> toY == null || y >= toY - 80
                else -> true
            }
            if (!ok) return null
        }

        val xp = locatorFor(best)
        return Locator(Strategy.XPATH, xp) to best
    }

    // --- helpers ---

    private fun norm(s: String): String =
        s.lowercase()
            .replace("&", " and ")
            .replace(Regex("[\\p{Punct}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun scoreLabel(needle: String, hay: String): Int {
        val h = norm(hay)
        if (h == needle) return 3
        if (h.contains(needle)) return 2
        val toks = needle.split(" ").filter { it.isNotBlank() }
        val hit = toks.count { h.contains(it) }
        return if (hit >= toks.size.coerceAtLeast(1)) 1 else 0
    }

    private fun locatorFor(el: WebElement): String {
        val rid = runCatching { el.getAttribute("resource-id") }.getOrNull()?.takeIf { it.isNotBlank() }
        if (rid != null) return "//*[@resource-id='$rid']"
        val txt = runCatching { el.text }.getOrNull()?.trim().orEmpty()
        if (txt.isNotEmpty()) return "//*[normalize-space(@text)='${txt.replace("'", "’")}']"
        val clickable = runCatching { el.getAttribute("clickable") }.getOrNull()
        return if (clickable == "true") "(//*[@clickable='true'])[1]" else "//*[@checkable='true'][1]"
    }
}
