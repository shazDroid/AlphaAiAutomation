package agent.xpath

import org.openqa.selenium.By
import org.openqa.selenium.WebElement

object XPathUtil {
    fun robustXPathFor(el: WebElement): String {
        val rid = attr(el, "resource-id")
        if (rid.isNotBlank()) return "//*[@resource-id=${lit(rid)}]"
        val desc = attr(el, "content-desc")
        if (desc.isNotBlank()) return "//*[@content-desc=${lit(desc)}]"
        val text = el.text?.trim().orEmpty()
        if (text.isNotEmpty()) return "//*[normalize-space(@text)=${lit(text)}]"
        var cur: WebElement? = el
        var suffix = ""
        repeat(6) {
            if (cur == null) return@repeat
            val cls = attr(cur!!, "className").ifBlank { cur!!.tagName }
            val idx = indexInParent(cur!!, cls)
            suffix = "/$cls[$idx]$suffix"
            val parent = cur!!.findElements(By.xpath("..")).firstOrNull()
            val pid = parent?.getAttribute("resource-id")?.trim().orEmpty()
            if (pid.isNotEmpty()) return "//*[@resource-id=${lit(pid)}]$suffix"
            cur = parent
        }
        return "/*$suffix"
    }

    private fun indexInParent(el: WebElement, cls: String): Int {
        val preceding = el.findElements(By.xpath("preceding-sibling::*[self::$cls]")).size
        return preceding + 1
    }

    private fun attr(el: WebElement, name: String) = (runCatching { el.getAttribute(name) }.getOrNull() ?: "").trim()

    private fun lit(s: String) = when {
        !s.contains("'") -> "'$s'"
        !s.contains("\"") -> "\"$s\""
        else -> "concat('${s.replace("'", "',\"'\",'")}')"
    }
}
