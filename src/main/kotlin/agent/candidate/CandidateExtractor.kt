package agent.candidates

import io.appium.java_client.AppiumBy
import io.appium.java_client.android.AndroidDriver
import org.openqa.selenium.By
import org.openqa.selenium.WebElement
import java.util.*

data class UICandidate(
    val id: String,             // stable within this screen (we’ll generate)
    val label: String,
    val xpath: String,          // stable, no bounds; clickable ancestor anchored by container if possible
    val role: String,           // "bottom_nav", "tab", "button", "chip", "list_item", "dialog_button", "other"
    val clickable: Boolean,
    val containerId: String?,   // nav/tab container resource-id if detected
)

private fun String.norm(): String =
    lowercase(Locale.ENGLISH).replace(Regex("&|and"), " ").replace(Regex("[^a-z0-9]+"), " ").trim()

private fun xpathLiteral(s: String): String =
    when {
        '\'' !in s -> "'$s'"
        '"' !in s -> "\"$s\""
        else -> "concat('${s.replace("'", "',\"'\",'")}')"
    }

private fun clickableAncestor(el: WebElement): WebElement {
    var node = el
    repeat(8) {
        if (runCatching { node.getAttribute("clickable") }.getOrNull() == "true") return node
        node = runCatching { node.findElement(By.xpath("..")) }.getOrNull() ?: return el
    }
    return el
}

private fun navContainerIdOrNull(el: WebElement): String? {
    var node: WebElement? = el
    repeat(8) {
        node ?: return@repeat
        val rid = runCatching { node!!.getAttribute("resource-id") }.getOrNull() ?: ""
        val cls = runCatching { node!!.getAttribute("className") }.getOrNull() ?: ""
        if (rid.contains("nav", true) || rid.contains("tab", true) || rid.contains("bottom", true) ||
            cls.contains("BottomNavigationView") || cls.contains("TabLayout") || cls.contains("BottomAppBar")
        ) {
            return rid.ifBlank { null }
        }
        node = runCatching { node!!.findElement(By.xpath("..")) }.getOrNull()
    }
    return null
}

private fun bestTapXPathFor(el: WebElement, labelFallback: String): String {
    val labelText = (runCatching { el.text }.getOrNull() ?: "").trim()
    val desc = (runCatching { el.getAttribute("content-desc") }.getOrNull() ?: "").trim()
    val label = if (labelText.isNotEmpty()) labelText else desc
    val labelLit = xpathLiteral(if (label.isNotEmpty()) label else labelFallback)
    val rid = navContainerIdOrNull(el)
    val labelNode = "(*[@text=$labelLit or @content-desc=$labelLit])"
    val clickNode = "($labelNode/ancestor-or-self::*[@clickable='true'])[1]"
    return if (!rid.isNullOrBlank())
        "//*[@resource-id=${xpathLiteral(rid)}]//$clickNode"
    else
        "//$clickNode"
}

private fun roleFor(el: WebElement, label: String): String {
    val cls = runCatching { el.getAttribute("className") }.getOrNull() ?: ""
    val rid = navContainerIdOrNull(el)
    return when {
        !rid.isNullOrBlank() && (rid.contains("nav", true) || rid.contains("bottom", true)) -> "bottom_nav"
        !rid.isNullOrBlank() && rid.contains("tab", true) -> "tab"
        cls.contains("Button") -> "button"
        cls.contains("Chip") -> "chip"
        cls.contains("RecyclerView") || cls.contains("List") -> "list_item"
        label.equals("ok", true) || label.equals("cancel", true) -> "dialog_button"
        else -> "other"
    }
}

/**
 * Build a candidate set for the current screen from accessibility text/desc.
 * (OCR can be merged here later if you add an OCR provider.)
 */
fun extractCandidatesForHint(
    driver: AndroidDriver,
    hint: String,
    limit: Int = 20
): List<UICandidate> {
    val h = hint.trim()
    if (h.isEmpty()) return emptyList()

    val lit = xpathLiteral(h)
    val exact = driver.findElements(
        AppiumBy.xpath("//*[( @text=$lit or @content-desc=$lit )]")
    )
    val tokens = h.norm().split(" ").filter { it.isNotBlank() }.toSet()

    val any = if (tokens.isNotEmpty()) {
        val anyExpr = tokens.joinToString(" or ") {
            "contains(translate(@text,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'$it') " +
                    "or contains(translate(@content-desc,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'$it')"
        }
        driver.findElements(AppiumBy.xpath("//*[( $anyExpr )]"))
    } else emptyList()

    val all = (exact + any).distinct()
    return all.take(limit).mapIndexed { idx, base ->
        val click = clickableAncestor(base)
        val label = (runCatching { base.text }.getOrNull()
            ?: runCatching { base.getAttribute("content-desc") }.getOrNull() ?: "").ifBlank { h }
        val xp = bestTapXPathFor(click, h)
        val role = roleFor(click, label)
        val clickable = runCatching { click.getAttribute("clickable") }.getOrNull() == "true"
        UICandidate(
            id = "c$idx",
            label = label,
            xpath = xp,
            role = role,
            clickable = clickable,
            containerId = navContainerIdOrNull(click)
        )
    }
}
