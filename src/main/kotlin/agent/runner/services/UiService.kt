package agent.runner.services

import adb.UiDumpParser.xpathLiteral
import agent.Locator
import agent.StepType
import agent.runner.RunContext
import io.appium.java_client.AppiumBy
import org.openqa.selenium.By
import org.openqa.selenium.WebElement
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * UI utilities for visibility, scrolling, scoping and quick actions.
 */
class UiService(
    private val ctx: RunContext,
    private val vision: VisionService,
    private val xpaths: XPathService
) {
    fun isFullyVisible(el: WebElement): Boolean {
        val vp = ctx.driver.manage().window().size
        val r = el.rect
        val topSafe = 40
        val botSafe = vp.height - 40
        return r.y >= topSafe && (r.y + r.height) <= botSafe
    }

    fun firstClickable(el: WebElement): WebElement {
        return if (runCatching { el.getAttribute("clickable") }.getOrNull() == "true") el
        else el.findElements(By.xpath("ancestor::*[@clickable='true'][1]")).firstOrNull() ?: el
    }

    fun attr(el: WebElement, name: String): String {
        val v = runCatching { el.getAttribute(name) }.getOrNull() ?: return ""
        val s = v.trim()
        return if (s.equals("null", true) || s.equals("none", true)) "" else s
    }

    fun tapByTextInSection(label: String, section: String): Locator? {
        val candidates = findElementsByTextScoped(label, section)
        val el = candidates.firstOrNull() ?: return null
        val clickEl = firstClickable(el)
        val before = ctx.pageHash()
        runCatching { clickEl.click() }.onFailure { return null }
        ctx.lastTapY = runCatching { clickEl.rect.let { it.y + it.height / 2 } }.getOrNull()
        ctx.setScope(vision.determineScopeByY(ctx.lastTapY, vision.fast(section)) ?: ctx.activeScope)
        val xp = buildLocatorForElement(clickEl)
        if (changedSince(before, 1500L)) return xpaths.locatorOf(xp)
        return null
    }

    fun ensureVisibleByAutoScrollBounded(
        label: String,
        section: String?,
        stepIndex: Int,
        stepType: StepType,
        maxSwipes: Int
    ) {
        agent.ui.Gestures.hideKeyboardIfOpen(ctx.driver) { }
        ctx.resolver.waitForStableUi()
        if (isTargetVisibleNow(label, section)) return
        var swipes = 0
        while (swipes < maxSwipes) {
            agent.ui.Gestures.standardScrollUp(ctx.driver)
            swipes++
            ctx.resolver.waitForStableUi()
            if (isTargetVisibleNow(label, section)) break
        }
        if (swipes > 0) {
            ctx.onLog("auto-scroll ×$swipes before $stepType \"$label\"")
            ctx.store.capture(stepIndex, StepType.SCROLL_TO, label, null, true, "auto=1;before=$stepType;count=$swipes")
            ctx.flowRecorder?.addStep(StepType.SCROLL_TO, label)
        }
    }

    fun waitWhileBusy(maxMs: Long): Boolean {
        val start = System.currentTimeMillis()
        var sawBusy = false
        while (System.currentTimeMillis() - start < maxMs) {
            if (isBusy()) {
                sawBusy = true; Thread.sleep(200); continue
            }
            if (sawBusy) Thread.sleep(150)
            return sawBusy
        }
        return sawBusy
    }

    fun changedSince(prevHash: Int, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val cur = ctx.pageHash()
            if (cur != prevHash) return true
            Thread.sleep(120)
        }
        return false
    }

    fun waitChangedSince(prevHash: Int, timeoutMs: Long): Boolean = changedSince(prevHash, timeoutMs)

    fun parseSectionFromHint(hint: String): String? {
        val s = hint.lowercase()
        if (Regex("\\bfor\\s+from\\b|\\bin\\s+from\\b|\\bfrom\\s*:\\s*").containsMatchIn(s)) return "from"
        if (Regex("\\bfor\\s+to\\b|\\bin\\s+to\\b|\\bto\\s*:\\s*").containsMatchIn(s)) return "to"
        return null
    }

    fun tryCheckByLocator(loc: Locator, desired: Boolean?): Boolean {
        return try {
            val by = xpaths.toBy(loc)
            val el = runCatching { ctx.driver.findElement(by) }.getOrNull() ?: return false
            val isChecked = (runCatching { el.getAttribute("checked") }.getOrNull() ?: "false") == "true"
            val want = desired ?: !isChecked
            if (isChecked != want) el.click()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun buildLocatorForElement(el: WebElement): String {
        val rid = attr(el, "resource-id")
        if (rid.isNotBlank()) return "//*[@resource-id=${xpathLiteral(rid)}]"
        val txt = (runCatching { el.text }.getOrNull() ?: attr(el, "text")).trim()
        if (txt.isNotEmpty()) return "//*[normalize-space(@text)=${xpathLiteral(txt)}]"
        val desc = attr(el, "content-desc")
        if (desc.isNotEmpty()) return "//*[@content-desc=${xpathLiteral(desc)}]"
        return "(.//*[@clickable='true'])[1]"
    }

    fun findElementsByTextScoped(label: String, section: String?): List<WebElement> {
        val allText = ctx.driver.findElements(AppiumBy.xpath("//*[normalize-space(@text)!='']"))
        val target = normalize(label)
        val matched = allText.filter { normalize(runCatching { it.text }.getOrNull()) == target }
        if (matched.isEmpty() || section == null) return matched
        val headers = detectHeaders()
        return when (headers.layout) {
            Layout.HORIZONTAL -> {
                val splitX = headers.splitX
                val wantLeft = section.equals("from", true)
                matched.filter { el ->
                    val r = el.rect
                    val cx = r.x + r.width / 2
                    val onLeft = cx <= splitX
                    if (headers.hasBoth) if (wantLeft) onLeft else !onLeft
                    else abs(cx - headers.anchorX) < max(headers.anchorW, 120)
                }.sortedBy { el ->
                    val r = el.rect
                    abs((r.x + r.width / 2) - (if (wantLeft) headers.fromX else headers.toX))
                }
            }

            Layout.VERTICAL, Layout.UNKNOWN -> {
                val fromY = headers.fromY
                val toY = headers.toY
                val filtered = matched.filter { el ->
                    val r = el.rect
                    val cy = r.y + r.height / 2
                    when (section.lowercase()) {
                        "from" -> when {
                            fromY != null && toY != null -> cy in min(fromY, toY)..max(fromY, toY)
                            fromY != null -> cy >= fromY + 4
                            toY != null -> cy < toY - 4
                            else -> true
                        }

                        "to" -> when {
                            toY != null -> cy >= toY + 4
                            fromY != null -> cy > fromY + 4
                            else -> true
                        }

                        else -> true
                    }
                }
                val headerCy = if (section.equals("from", true)) headers.fromY else headers.toY
                if (headerCy == null) filtered else filtered.sortedBy { el ->
                    val r = el.rect
                    abs((r.y + r.height / 2) - headerCy)
                }
            }
        }
    }

    fun nearestCheckableNear(centerY: Int?): Pair<Locator, WebElement>? {
        val nodes = ctx.driver.findElements(AppiumBy.xpath("//android.widget.Switch | //*[@checkable='true']"))
        if (nodes.isEmpty()) return null
        val cy = centerY ?: ctx.driver.manage().window().size.height / 2
        val best = nodes.minByOrNull { el -> abs((el.rect.y + el.rect.height / 2) - cy) } ?: return null
        return xpaths.locatorOf(buildLocatorForElement(best)) to best
    }

    private fun isTargetVisibleNow(label: String, section: String?): Boolean {
        val domVisible = runCatching {
            ctx.driver.findElements(AppiumBy.xpath(buildTokenXPathAny(label)))
        }.getOrNull().orEmpty().firstOrNull()?.let { isFullyVisible(it) } ?: false
        return domVisible || visionFastContains(label, section)
    }

    private fun buildTokenXPathAny(label: String): String {
        val toks = label.lowercase().replace("&", " ").split(Regex("\\s+"))
            .map { it.trim('\'', '"', '.', ',', ':', ';', '!', '?') }
            .filter { it.length >= 3 && it.any(Char::isLetter) && it !in STOP }
        if (toks.isEmpty()) return "//*"
        val cond = toks.joinToString(" or ") {
            "(contains(translate(@text,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),${xpathLiteral(it)}) or contains(translate(@content-desc,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),${
                xpathLiteral(
                    it
                )
            }))"
        }
        return "//*[$cond]"
    }

    private fun visionFastContains(label: String, section: String?): Boolean {
        val v = vision.fast(section) ?: return false
        val toks = label.lowercase().replace("&", " ").split(Regex("\\s+")).filter { it.length >= 3 }
        if (toks.isEmpty()) return false
        val vp = ctx.driver.manage().window().size
        val topSafe = 40
        val botSafe = vp.height - 40
        return v.elements.any { e ->
            val t = e.text ?: return@any false
            val y = e.y ?: return@any false
            val h = e.h ?: return@any false
            y >= topSafe && (y + h) <= botSafe && toks.any { tok -> t.contains(tok, true) }
        }
    }

    private fun isBusy(): Boolean {
        val xp =
            "//*[@indeterminate='true' and self::android.widget.ProgressBar] | //*[contains(translate(@content-desc,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'progress') and @clickable='false']"
        return runCatching { ctx.driver.findElements(AppiumBy.xpath(xp)).isNotEmpty() }.getOrDefault(false)
    }

    private fun normalize(s: String?): String = (s ?: "")
        .lowercase().replace("&", " and ")
        .replace(Regex("[\\p{Punct}]"), " ")
        .replace(Regex("(.)\\1{2,}"), "$1$1")
        .replace(Regex("\\s+"), " ").trim()

    private val STOP = setOf("to", "for", "and", "the", "my", "your", "a", "an", "of", "on", "in", "at", "with")

    private enum class Layout { HORIZONTAL, VERTICAL, UNKNOWN }

    private data class Headers(
        val layout: Layout,
        val hasBoth: Boolean,
        val fromY: Int?, val toY: Int?,
        val fromX: Int, val toX: Int,
        val anchorX: Int, val anchorW: Int,
        val splitX: Int
    )

    private fun detectHeaders(): Headers {
        val v = vision.fast(null)
        val fromV = v?.elements?.firstOrNull { (it.text ?: "").equals("from", true) }
        val toV = v?.elements?.firstOrNull { (it.text ?: "").equals("to", true) }
        val fromDom = runCatching {
            ctx.driver.findElements(AppiumBy.xpath("//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='from']"))
                .firstOrNull()
        }.getOrNull()
        val toDom = runCatching {
            ctx.driver.findElements(AppiumBy.xpath("//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='to']"))
                .firstOrNull()
        }.getOrNull()
        val fY = fromV?.y ?: fromDom?.rect?.y
        val tY = toV?.y ?: toDom?.rect?.y
        val fX = fromV?.x ?: fromDom?.rect?.let { it.x + it.width / 2 } ?: 0
        val tX = toV?.x ?: toDom?.rect?.let { it.x + it.width / 2 } ?: 0
        val fW = fromV?.w ?: fromDom?.rect?.width ?: 0
        val tW = toV?.w ?: toDom?.rect?.width ?: 0
        val hasBoth = fY != null && tY != null
        val layout = if (hasBoth && abs(
                (fY ?: 0) - (tY ?: 0)
            ) < 80
        ) Layout.HORIZONTAL else if (fY != null || tY != null) Layout.VERTICAL else Layout.UNKNOWN
        val splitX = if (hasBoth) (fX + tX) / 2 else if (fX != 0) fX else tX
        val anchorX = if (fX != 0) fX else tX
        val anchorW = if (fW != 0) fW else tW
        return Headers(layout, hasBoth, fY, tY, fX, tX, anchorX, anchorW, splitX)
    }
}
