package agent

import io.appium.java_client.AppiumBy
import io.appium.java_client.android.AndroidDriver
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.openqa.selenium.By

class LocatorResolver(
    private val driver: AndroidDriver,
    private val log: (String) -> Unit = {}
) {
    fun resolve(targetHint: String): Locator = resolve(targetHint, log)

    fun resolve(targetHint: String, logger: (String) -> Unit): Locator {
        val hint = targetHint.trim()
        fun tryQuery(strategy: Strategy, name: String, by: () -> By): Locator? = try {
            logger("try $name")
            driver.findElement(by())
            val v = by().toString().substringAfter(": ").ifBlank { by().toString() }
            logger("…found via $name -> $v")
            Locator(strategy, v)
        } catch (_: Exception) { null }

        tryQuery(Strategy.ID, "id($hint)") { AppiumBy.id(hint) }?.let { return it }
        tryQuery(Strategy.UIAUTOMATOR, """text("$hint")""") {
            AppiumBy.androidUIAutomator("""new UiSelector().text("$hint")""")
        }?.let { return it }
        tryQuery(Strategy.UIAUTOMATOR, """textContains("$hint")""") {
            AppiumBy.androidUIAutomator("""new UiSelector().textContains("$hint")""")
        }?.let { return it }
        tryQuery(Strategy.UIAUTOMATOR, """descriptionContains("$hint")""") {
            AppiumBy.androidUIAutomator("""new UiSelector().descriptionContains("$hint")""")
        }?.let { return it }
        tryQuery(Strategy.XPATH, "xpath(text|contains)") {
            AppiumBy.xpath("//*[@text='$hint' or contains(@text,'$hint')]")
        }?.let { return it }

        logger("fallback xpath contains: $hint")
        return Locator(Strategy.XPATH, "//*[*[contains(@text,'$hint')]]")
    }

    /**
     * Input-aware resolver that:
     *  - matches id patterns (case-insensitive)
     *  - walks from label TextView → descendant/sibling editable
     *  - supports custom classes (TextInputEditText, CustomEditText, etc.)
     */
    fun resolveForInputAdvanced(targetHint: String, logger: (String) -> Unit = log): Locator {
        val h = targetHint.trim()
        val hLower = h.lowercase()

        // id contains / synonyms
        fun tryQuery(strategy: Strategy, label: String, by: () -> By): Locator? = try {
            logger("try $label")
            driver.findElement(by())
            val v = by().toString().substringAfter(": ").ifBlank { by().toString() }
            logger("…found via $label -> $v")
            Locator(strategy, v)
        } catch (_: Exception) { null }

        val syn = when {
            "user" in hLower -> "(?i).*(user(name)?|login|email).*"
            "pass" in hLower -> "(?i).*(pass(word)?|pwd).*"
            "mobile" in hLower || "phone" in hLower -> "(?i).*(mobile|phone|msisdn).*"
            else -> "(?i).*${Regex.escape(hLower)}.*"
        }

        tryQuery(Strategy.UIAUTOMATOR, """resourceIdMatches("$syn")""") {
            AppiumBy.androidUIAutomator("""new UiSelector().resourceIdMatches("$syn")""")
        }?.let { return it }

        // parse XML and find nearest editable around label
        val xml = driver.pageSource
        val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())

        // label nodes text contains the hint (case-insensitive)
        val labels = doc.select("node[text~=(?i).*${Regex.escape(hLower)}.*], node[content-desc~=(?i).*${Regex.escape(hLower)}.*]")
        logger("labels matched: ${labels.size}")

        // Given a label node, search its following siblings and ancestors for an editable
        fun isEditable(e: Element): Boolean {
            val cls = e.attr("class")
            val focusable = e.attr("focusable") == "true"
            val clickable = e.attr("clickable") == "true"
            val editableClass = cls.contains("EditText", ignoreCase = true)
                    || cls.contains("TextInputEditText", ignoreCase = true)
                    || cls.contains("AutoCompleteTextView", ignoreCase = true)
                    || cls.contains("Custom", ignoreCase = true) && (focusable || clickable)
            return editableClass || (focusable && clickable)
        }

        fun elementToXPath(e: Element): String {
            // Build an absolute XPath by walking parents with index
            val parts = mutableListOf<String>()
            var cur: Element? = e
            while (cur != null && cur.tagName() == "node") {
                val parent = cur.parent()
                val idx = parent?.children()?.filter { it.tagName() == "node" }?.indexOf(cur) ?: -1
                val index1 = if (idx >= 0) idx + 1 else 1
                val clazz = cur.attr("class")
                val step = if (clazz.isNotBlank())
                    "*[@class='${clazz}'][$index1]"
                else
                    "node[$index1]"
                parts.add(step)
                cur = parent
            }
            return "//" + parts.asReversed().joinToString("/")
        }

        for (label in labels) {
            label.siblingElements().forEach { sib ->
                val cand = sib.selectFirst("node[class~=.*]") ?: sib
                if (isEditable(cand)) {
                    val xp = elementToXPath(cand)
                    logger("label→sibling editable: $xp")
                    return Locator(Strategy.XPATH, xp)
                }
            }

            var anc: Element? = label.parent()
            repeat(4) {
                if (anc == null) return@repeat
                val edit = anc!!.selectFirst("node[class~=.*(EditText|AutoCompleteTextView|TextInputEditText|Custom).*]")
                if (edit != null) {
                    val xp = elementToXPath(edit)
                    logger("label→ancestor→descendant editable: $xp")
                    return Locator(Strategy.XPATH, xp)
                }
                anc = anc!!.parent()
            }
        }

        // fallback: first visible/focusable editable
        val anyEdit = doc.select("node[class~=.*(EditText|AutoCompleteTextView|TextInputEditText).*], node[focusable=true]")
            .firstOrNull()
        if (anyEdit != null) {
            val xp = elementToXPath(anyEdit)
            logger("fallback editable: $xp")
            return Locator(Strategy.XPATH, xp)
        }

        logger("ultimate fallback: //*[contains(@class,'EditText')][1]")
        return Locator(Strategy.XPATH, "(//*[contains(@class,'EditText')])[1]")
    }

    fun scrollToText(text: String) {
        log("scrollToText(\"$text\")")
        driver.findElement(
            AppiumBy.androidUIAutomator(
                """new UiScrollable(new UiSelector().scrollable(true))
                   .scrollIntoView(new UiSelector().textContains("$text"))"""
            )
        )
    }

    fun waitForText(text: String, timeoutMs: Long = 8000) {
        log("waitForText(\"$text\", $timeoutMs ms)")
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (driver.pageSource.contains(text)) return
            Thread.sleep(250)
        }
        error("WAIT_TEXT timeout: $text")
    }

    fun assertText(text: String) {
        log("assertText(\"$text\")")
        check(driver.pageSource.contains(text)) { "ASSERT_TEXT failed: $text" }
    }
}
