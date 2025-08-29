package agent.vision

import agent.Locator
import agent.Strategy
import io.appium.java_client.AppiumBy
import io.appium.java_client.android.AndroidDriver
import org.openqa.selenium.WebElement
import kotlin.math.abs
import kotlin.math.max

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

        val rowTop = t.y - max(48, t.h / 2)
        val rowBottom = t.y + t.h + max(48, t.h / 2)
        val midRight = t.x + (t.w * 3) / 5
        val textCy = t.y + t.h / 2

        val xp =
            "(.//*[" +
                    "@checkable='true' or @clickable='true' or @long-clickable='true' or " +
                    "contains(@class,'Switch') or contains(@class,'SwitchCompat') or " +
                    "contains(@class,'MaterialSwitch') or contains(@class,'Toggle') or " +
                    "contains(@class,'Radio') or contains(@class,'Check') or " +
                    "contains(translate(@resource-id,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'switch') or " +
                    "contains(translate(@resource-id,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'toggle') or " +
                    "contains(translate(@resource-id,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'radio')" +
                    "])"

        val nodes = driver.findElements(AppiumBy.xpath(xp))

        data class DomCand(val el: WebElement, val score: Int)

        val candidates = nodes.mapNotNull { el ->
            val r = runCatching { el.rect }.getOrNull() ?: return@mapNotNull null
            val cy = r.y + r.height / 2
            val cx = r.x + r.width / 2
            val sameRow = cy in rowTop..rowBottom
            val rightSide = cx > midRight
            if (!sameRow || !rightSide) return@mapNotNull null
            val dy = abs(cy - textCy)
            val dx = max(0, cx - midRight)
            val cls = runCatching { el.getAttribute("class") }.getOrNull().orEmpty()
            val checkable = runCatching { el.getAttribute("checkable") }.getOrNull() == "true"
            val weight =
                when {
                    cls.contains("MaterialSwitch", true) -> 0
                    cls.contains("SwitchCompat", true) -> 1
                    cls.contains("Switch", true) -> 2
                    cls.contains("CheckBox", true) -> 4
                    cls.contains("Radio", true) -> 5
                    else -> 6
                } - if (checkable) 1 else 0
            DomCand(el, dy * 6 + dx * 2 + weight)
        }.sortedBy { it.score }

        val picked: WebElement? = candidates.firstOrNull()?.el ?: run {
            val lower = label.lowercase()
            val labelXpath =
                "//*[contains(translate(@text,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),${xpathLiteral(lower)}) or " +
                        "contains(translate(@content-desc,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),${xpathLiteral(lower)})]"
            val labelEls = driver.findElements(AppiumBy.xpath(labelXpath))
            val anchor = labelEls.firstOrNull() ?: return null
            val ar = runCatching { anchor.rect }.getOrNull() ?: return null
            val ay = ar.y + ar.height / 2
            val axRight = ar.x + (ar.width * 3) / 5
            val rowMin = ar.y - max(48, ar.height / 2)
            val rowMax = ar.y + ar.height + max(48, ar.height / 2)
            val cands = driver.findElements(AppiumBy.xpath("//*[@clickable='true' or @checkable='true']"))
            cands.mapNotNull { el ->
                val rr = runCatching { el.rect }.getOrNull() ?: return@mapNotNull null
                val cy = rr.y + rr.height / 2
                val cx = rr.x + rr.width / 2
                val sameRow = cy in rowMin..rowMax
                val rightSide = cx > axRight
                if (!sameRow || !rightSide) null else DomCand(el, abs(cy - ay) * 6 + max(0, cx - axRight) * 2)
            }.sortedBy { it.score }
                .firstOrNull()
                ?.el
        }

        if (picked == null) return null
        val locator = Locator(Strategy.XPATH, buildLocatorForElement(picked))
        onLog?.invoke("vision:toggle picked xpath=${locator.value}")
        return locator to picked

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
        val cls = safeAttr(el, "class")
        if (cls.isNotBlank()) return "//*[@class=${xpathLiteral(cls)}][last()]"
        return "(.//*[@clickable='true' or @checkable='true'])[last()]"
    }


    private fun safeAttr(el: WebElement, name: String): String =
        runCatching { el.getAttribute(name) }.getOrNull()?.trim().orEmpty()

    private fun xpathLiteral(s: String): String = when {
        '\'' !in s -> "'$s'"
        '"' !in s -> "\"$s\""
        else -> "concat('${s.replace("'", "',\"'\",'")}')"
    }
}
