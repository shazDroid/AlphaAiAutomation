package agent.vision

import agent.Locator
import agent.Strategy
import io.appium.java_client.AppiumBy
import io.appium.java_client.android.AndroidDriver
import org.openqa.selenium.WebElement

/**
 * Helpers to connect OCR (VisionResult) with the live DOM.
 */
object ScreenVision {

    /**
     * Given a label (e.g., "Move all my money"), find the best OCR text match and
     * then locate the nearest switch/checkbox to the right on the same row.
     *
     * Returns (Locator, WebElement) or null if not found.
     */
    fun findToggleForLabel(
        driver: AndroidDriver,
        vr: VisionResult,
        label: String,
        section: String? = null,
        onLog: ((String) -> Unit)? = null
    ): Pair<Locator, WebElement>? {
        val t = bestTextMatch(vr, label) ?: run {
            onLog?.invoke("vision:toggle label '$label' not found in vision elements")
            return null
        }

        onLog?.invoke("vision:label match text='${t.text}' at (${t.x},${t.y}) size=${t.w}x${t.h}")

        // Candidate DOM nodes that could be toggles/switches/checkboxes.
        val xp =
            "(.//*[" +
                    "@checkable='true' or " +
                    "contains(@class,'Switch') or contains(@class,'SwitchCompat') or " +
                    "contains(@class,'MaterialSwitch') or contains(@class,'Toggle') or " +
                    "contains(@class,'Radio') or contains(@class,'CheckBox') or " +
                    "contains(translate(@resource-id,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'switch') or " +
                    "contains(translate(@resource-id,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'toggle') or " +
                    "contains(translate(@content-desc,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'switch') or " +
                    "contains(translate(@content-desc,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'toggle')" +
                    "])"

        val nodes = driver.findElements(AppiumBy.xpath(xp))
        if (nodes.isEmpty()) return null

        data class DomCand(val el: WebElement, val score: Int)

        val textCy = t.y + t.h / 2
        val textRightEdge = t.x + t.w - 4
        val rowTop = t.y - 48
        val rowBottom = t.y + t.h + 48

        val candidates = nodes.mapNotNull { el ->
            val r = runCatching { el.rect }.getOrNull() ?: return@mapNotNull null
            val cy = r.y + r.height / 2
            val sameRow = cy in rowTop..rowBottom
            val rightSide = r.x >= textRightEdge
            if (!sameRow || !rightSide) return@mapNotNull null
            val score = kotlin.math.abs(cy - textCy) * 3 + kotlin.math.max(0, r.x - textRightEdge)
            DomCand(el, score)
        }.sortedBy { it.score }

        val best = candidates.firstOrNull() ?: run {
            onLog?.invoke("vision: no right-side toggle in same row for '${t.text}'")
            return null
        }

        val xpLoc = buildLocatorForElement(best.el)
        onLog?.invoke(
            "vision:toggle picked DOM rid='${safeAttr(best.el, "resource-id")}', desc='${
                safeAttr(
                    best.el,
                    "content-desc"
                )
            }', text='${safeAttr(best.el, "text")}', xpath=$xpLoc"
        )
        return Locator(Strategy.XPATH, xpLoc) to best.el
    }

    /**
     * Use OCR anchors "from"/"to" to keep candidates within matching section, if available.
     * Falls back to returning 'all' when anchors are not found.
     */
    fun <T> restrictCandidatesToSection(
        driver: AndroidDriver,
        all: List<T>,
        vr: VisionResult,
        section: String,
        getXpath: (T) -> String
    ): List<T> {
        val anchorY = when (section.lowercase()) {
            "from" -> firstY(vr, "from")
            "to" -> firstY(vr, "to")
            else -> null
        } ?: return all

        return all.filter { item ->
            val rect = runCatching { driver.findElement(AppiumBy.xpath(getXpath(item))).rect }.getOrNull()
            rect?.y?.let { it >= anchorY - 40 } ?: true
        }
    }

    private fun firstY(vr: VisionResult, token: String): Int? =
        vr.elements.firstOrNull { it.text?.equals(token, ignoreCase = true) == true }?.y

    /** Pick the OCR element that best matches the label. */
    fun bestTextMatch(vr: VisionResult, label: String): VisionElement? =
        vr.elements
            .asSequence()
            .filter { it.text?.isNotBlank() == true }
            .map { it to scoreText(it.text!!, label) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= 0.50 }
            ?.first

    /** Soft string scoring that rewards full/partial token overlaps and contains() matches. */
    private fun scoreText(a: String, b: String): Double {
        val aa = norm(a);
        val bb = norm(b)
        if (aa == bb || aa.contains(bb) || bb.contains(aa)) return 1.0
        val at = aa.split(' ').filter { it.isNotBlank() }.toSet()
        val bt = bb.split(' ').filter { it.isNotBlank() }.toSet()
        val overlap = at.intersect(bt).size.toDouble()
        val union = (at + bt).toSet().size.coerceAtLeast(1).toDouble()
        return overlap / union
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun buildLocatorForElement(el: WebElement): String {
        val rid = safeAttr(el, "resource-id")
        if (rid.isNotBlank()) return "//*[@resource-id=${xpathLiteral(rid)}]"
        val desc = safeAttr(el, "content-desc")
        if (desc.isNotBlank()) return "//*[@content-desc=${xpathLiteral(desc)}]"
        val txt = safeAttr(el, "text")
        if (txt.isNotBlank()) return "//*[normalize-space(@text)=${xpathLiteral(txt)}]"
        // very last resort
        return "(.//*[@checkable='true' or self::android.widget.Switch])[1]"
    }

    private fun safeAttr(el: WebElement, name: String): String =
        runCatching { el.getAttribute(name) }.getOrNull()?.trim().orEmpty()

    private fun xpathLiteral(s: String): String = when {
        '\'' !in s -> "'$s'"
        '"' !in s -> "\"$s\""
        else -> "concat('${s.replace("'", "',\"'\",'")}')"
    }
}
