package agent.runner.services

import adb.UiDumpParser.xpathLiteral
import agent.Locator
import agent.Strategy
import agent.runner.RunContext
import io.appium.java_client.AppiumBy
import org.openqa.selenium.By
import org.openqa.selenium.WebElement
import org.openqa.selenium.remote.RemoteWebElement

/**
 * XPath validation and conversion helpers.
 */
class XPathService(private val ctx: RunContext) {
    data class ValidatedXPath(val xpath: String, val element: WebElement)

    fun validate(original: Locator): ValidatedXPath? {
        val live = ctx.driver.findElement(toBy(original))
        val candidates = buildCandidates(live)
        for (xp in candidates) {
            val matches = ctx.driver.findElements(AppiumBy.xpath(xp))
            if (matches.size == 1 && same(matches[0], live)) return ValidatedXPath(xp, matches[0])
        }
        for (xp in candidates) {
            val matches = ctx.driver.findElements(AppiumBy.xpath(xp))
            val same = matches.firstOrNull { same(it, live) }
            if (same != null) return ValidatedXPath(xp, same)
        }
        return null
    }

    fun toLocatorWith(vx: ValidatedXPath, original: Locator): Locator =
        Locator(strategy = Strategy.XPATH, value = vx.xpath, alternatives = listOf(original.strategy to original.value))

    fun locatorOf(xp: String): Locator = Locator(Strategy.XPATH, xp)

    fun toBy(loc: Locator): By = when (loc.strategy) {
        Strategy.XPATH -> AppiumBy.xpath(loc.value)
        Strategy.ID -> AppiumBy.id(loc.value)
        Strategy.DESC -> AppiumBy.accessibilityId(loc.value)
        Strategy.UIAUTOMATOR -> AppiumBy.androidUIAutomator(loc.value)
        else -> AppiumBy.xpath(loc.value)
    }

    private fun buildCandidates(el: WebElement): List<String> {
        val resId = attr(el, "resource-id")
        val contentDesc = attr(el, "content-desc").ifEmpty { attr(el, "contentDescription") }
        val txtRaw = (runCatching { el.text }.getOrNull() ?: attr(el, "text")).trim()
        fun eqText(t: String) = "//*[@text=${xpathLiteral(t)}]"
        fun eqTextCI(t: String) =
            "//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')=translate(${
                xpathLiteral(t)
            },'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')]"

        val out = mutableListOf<String>()
        if (resId.isNotEmpty()) out += "//*[@resource-id=${xpathLiteral(resId)}]"
        if (contentDesc.isNotEmpty()) out += "//*[@content-desc=${xpathLiteral(contentDesc)}]"
        if (txtRaw.isNotEmpty()) {
            out += eqText(txtRaw); out += eqTextCI(txtRaw)
        }
        if (out.isEmpty()) {
            val texts =
                el.findElements(By.xpath(".//android.widget.TextView[normalize-space(@text)!='' and @clickable='false']"))
                    .mapNotNull { runCatching { it.text }.getOrNull()?.trim() }.filter { it.isNotEmpty() }.distinct()
            val picks = texts.take(3)
            for (t in picks) {
                out += "(//*[normalize-space(@text)=${xpathLiteral(t)}]/ancestor::*[@clickable='true'][1])"
                out += "(//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')=translate(${
                    xpathLiteral(
                        t
                    )
                },'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')]/ancestor::*[@clickable='true'][1])"
            }
            out += "//*[self::android.widget.LinearLayout or self::android.view.ViewGroup][@clickable='true']"
        }
        return out.distinct()
    }

    private fun same(a: WebElement, b: WebElement): Boolean {
        val idA = (a as? RemoteWebElement)?.id
        val idB = (b as? RemoteWebElement)?.id
        if (!idA.isNullOrBlank() && !idB.isNullOrBlank()) return idA == idB
        val rA = (runCatching { a.getAttribute("resource-id") }.getOrNull() ?: "")
        val rB = (runCatching { b.getAttribute("resource-id") }.getOrNull() ?: "")
        val cA = (runCatching { a.getAttribute("className") }.getOrNull() ?: "")
        val cB = (runCatching { b.getAttribute("className") }.getOrNull() ?: "")
        val tA = (runCatching { a.text }.getOrNull() ?: "")
        val tB = (runCatching { b.text }.getOrNull() ?: "")
        val dA = (runCatching { a.getAttribute("content-desc") }.getOrNull() ?: "")
        val dB = (runCatching { b.getAttribute("content-desc") }.getOrNull() ?: "")
        return rA.isNotEmpty() && rA == rB && cA == cB && tA == tB && dA == dB
    }

    private fun attr(el: WebElement, name: String): String {
        val v = runCatching { el.getAttribute(name) }.getOrNull() ?: return ""
        val s = v.trim()
        return if (s.equals("null", true) || s.equals("none", true)) "" else s
    }
}
