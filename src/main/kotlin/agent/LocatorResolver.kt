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

    private val STOP_WORDS = setOf(
        "text","screen","page","button","label","field","tab","title",
        "item","link","menu","option"
    )

    private fun tokensOrdered(hint: String): List<String> =
        hint.split(Regex("[\\s_\\-]+"))
            .map { it.trim('\"','\'','.',',',':',';') }
            .filter { it.isNotBlank() && it.lowercase() !in STOP_WORDS }


    fun resolve(targetHint: String): Locator = resolve(targetHint, log)

    fun resolve(targetHint: String, logger: (String) -> Unit): Locator {
        val raw = targetHint.trim()
        val hint = raw
        logger("resolve: raw='$raw' → using='$hint'")

        fun tryQuery(strategy: Strategy, name: String, by: () -> By): Locator? = try {
            logger("try $name")
            driver.findElement(by())
            val v = by().toString().substringAfter(": ").ifBlank { by().toString() }
            logger("…found via $name -> $v")
            Locator(strategy, v)
        } catch (_: Exception) { null }

        val toks = tokensOrdered(hint)
        if (toks.size >= 2) {
            val lookahead = toks.joinToString("") { "(?=.*${Regex.escape(it)})" }
            val rx = "(?i)$lookahead.*"
            tryQuery(Strategy.UIAUTOMATOR, """textMatches("$rx") [multi-token]""") {
                AppiumBy.androidUIAutomator("""new UiSelector().textMatches("$rx")""")
            }?.let { return it }
            tryQuery(Strategy.UIAUTOMATOR, """descriptionMatches("$rx") [multi-token]""") {
                AppiumBy.androidUIAutomator("""new UiSelector().descriptionMatches("$rx")""")
            }?.let { return it }
        }

        tryQuery(Strategy.ID, "id($hint)") { AppiumBy.id(hint) }?.let { return it }

        tryQuery(Strategy.UIAUTOMATOR, """textMatches("(?i)^${Regex.escape(hint)}$")""") {
            AppiumBy.androidUIAutomator("""new UiSelector().textMatches("(?i)^${Regex.escape(hint)}$")""")
        }?.let { return it }
        tryQuery(Strategy.UIAUTOMATOR, """descriptionMatches("(?i)^${Regex.escape(hint)}$")""") {
            AppiumBy.androidUIAutomator("""new UiSelector().descriptionMatches("(?i)^${Regex.escape(hint)}$")""")
        }?.let { return it }

        for (t in toks) {
            tryQuery(Strategy.UIAUTOMATOR, """textContains("$t") [token]""") {
                AppiumBy.androidUIAutomator("""new UiSelector().textContains("$t")""")
            }?.let { return it }
            tryQuery(Strategy.UIAUTOMATOR, """descriptionContains("$t") [token]""") {
                AppiumBy.androidUIAutomator("""new UiSelector().descriptionContains("$t")""")
            }?.let { return it }
            tryQuery(Strategy.XPATH, """xpath contains("$t") [token]""") {
                AppiumBy.xpath("//*[(contains(@text,'$t') or contains(@content-desc,'$t'))]")
            }?.let { return it }
        }

        tryQuery(Strategy.UIAUTOMATOR, """textContains("$hint")""") {
            AppiumBy.androidUIAutomator("""new UiSelector().textContains("$hint")""")
        }?.let { return it }
        tryQuery(Strategy.UIAUTOMATOR, """descriptionContains("$hint")""") {
            AppiumBy.androidUIAutomator("""new UiSelector().descriptionContains("$hint")""")
        }?.let { return it }
        tryQuery(Strategy.XPATH, "xpath(text|desc|contains)") {
            AppiumBy.xpath("//*[(contains(@text,'$hint') or contains(@content-desc,'$hint'))]")
        }?.let { return it }

        logger("fallback (broad) contains for: $hint")
        return Locator(Strategy.XPATH, "//*[(contains(@text,'$hint') or contains(@content-desc,'$hint'))]")
    }

    // -----------------------------------------------------------------------
    // Smart waiter used by AgentRunner
    // -----------------------------------------------------------------------

    fun waitForElementPresent(
        targetHint: String,
        timeoutMs: Long = 15_000,
        clickIfFound: Boolean = false,
        logger: (String) -> Unit = log
    ): Locator {
        logger("waitForElementPresent(\"$targetHint\", timeoutMs=$timeoutMs, clickIfFound=$clickIfFound)")
        val end = System.currentTimeMillis() + timeoutMs
        var sleep = 150L
        var lastErr: Throwable? = null
        var scrolled = false

        val toks = tokensOrdered(targetHint)

        while (System.currentTimeMillis() < end) {
            waitForStableUi()
            runCatching { driver.hideKeyboard() }

            runCatching {
                val loc = resolve(targetHint, logger)
                val el = driver.findElement(loc.toBy())
                if (clickIfFound) el.click()
                logger("✓ present via resolve() ${loc.strategy} -> ${loc.value}")
                return loc
            }.onFailure { lastErr = it }

            if (!scrolled && toks.isNotEmpty()) {
                scrolled = true
                val t0 = toks.first()
                runCatching {
                    logger("scrollIntoView token '$t0'")
                    driver.findElement(
                        AppiumBy.androidUIAutomator(
                            """new UiScrollable(new UiSelector().scrollable(true))
                               .scrollIntoView(new UiSelector().textContains("$t0"))"""
                        )
                    )
                }
            }

            runCatching {
                rebuildXPathFromDump(targetHint, logger)?.let { xp ->
                    val loc = Locator(Strategy.XPATH, xp)
                    val el = driver.findElement(loc.toBy())
                    if (clickIfFound) el.click()
                    logger("✓ present via rebuilt xpath -> $xp")
                    return loc
                }
            }.onFailure { lastErr = it }

            Thread.sleep(sleep)
            sleep = (sleep * 1.4).toLong().coerceAtMost(800)
        }
        throw IllegalStateException("Timeout waiting for element: \"$targetHint\"${lastErr?.let { " (${it.message})" } ?: ""}")
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    fun scrollToText(text: String) {
        log("scrollToText(\"$text\")")
        runCatching {
            driver.findElement(
                AppiumBy.androidUIAutomator("""new UiSelector().textContains("$text")""")
            )
            log("…already visible, skipping scroll")
            return
        }

        driver.findElement(
            AppiumBy.androidUIAutomator(
                """new UiScrollable(new UiSelector().scrollable(true))
               .scrollIntoView(new UiSelector().textContains("$text"))"""
            )
        )
    }

    fun waitForText(query: String, timeoutMs: Long = 15_000) {
        log("waitForText(\"$query\", $timeoutMs ms)")
        val end = System.currentTimeMillis() + timeoutMs
        val regex = if (query.startsWith("regex:", true)) Regex(query.substringAfter(":"), RegexOption.IGNORE_CASE) else null
        val wantLen = if (query.startsWith("length:", true)) query.substringAfter(":").toIntOrNull() else null

        var sleep = 150L
        while (System.currentTimeMillis() < end) {
            val src = driver.pageSource // re-fetch each poll
            val hit = when {
                wantLen != null ->
                    Regex("""class=['"]android\.widget\.EditText['"][^>]*text=['"][0-9]{${wantLen}}['"]""")
                        .containsMatchIn(src)
                regex != null   -> regex.containsMatchIn(src)
                else            -> src.contains(query, ignoreCase = true)
            }
            if (hit) return
            Thread.sleep(sleep)
            sleep = (sleep * 1.4).toLong().coerceAtMost(800)
        }
        error("WAIT_TEXT timeout: $query")
    }

    fun waitForStableUi(maxQuietMs: Long = 1200, overallTimeoutMs: Long = 8000) {
        log("waitForStableUi")
        var last = driver.pageSource.hashCode()
        val end = System.currentTimeMillis() + overallTimeoutMs
        var quietStart = System.currentTimeMillis()
        while (System.currentTimeMillis() < end) {
            Thread.sleep(200)
            val h = driver.pageSource.hashCode()
            if (h != last) { last = h; quietStart = System.currentTimeMillis() }
            if (System.currentTimeMillis() - quietStart >= maxQuietMs) return
        }
    }

    fun assertText(text: String) {
        log("assertText(\"$text\")")
        check(driver.pageSource.contains(text)) { "ASSERT_TEXT failed: $text" }
    }

    // -----------------------------------------------------------------------
    // Self-heal from fresh XML (pageSource)
    // -----------------------------------------------------------------------

    private fun rebuildXPathFromDump(hint: String, logger: (String) -> Unit): String? {
        val xml = driver.pageSource
        val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
        val toks = tokensOrdered(hint)

        val queries = buildList {
            add(hint)
            addAll(toks)
        }
        return findNodeAndBuildXPath(doc, queries, toks, logger)
    }

    private fun findNodeAndBuildXPath(
        doc: org.jsoup.nodes.Document,
        queries: List<String>,
        allTokens: List<String>,
        logger: (String) -> Unit
    ): String? {
        data class Scored(val el: Element, val score: Int)

        fun coverageCount(str: String): Int =
            if (str.isEmpty()) 0 else allTokens.count { str.contains(it, ignoreCase = true) }

        fun inOrderBonus(str: String): Int {
            if (allTokens.size < 2 || str.isEmpty()) return 0
            var lastIdx = -1
            for (t in allTokens) {
                val i = str.lowercase().indexOf(t.lowercase(), lastIdx + 1)
                if (i < 0) return 0
                lastIdx = i
            }
            return 8
        }

        fun scoreFor(el: Element, q: String): Int {
            val t = el.attr("text"); val d = el.attr("content-desc")
            val clickable = el.attr("clickable") == "true"
            val focusable = el.attr("focusable") == "true"

            var s = when {
                t == q || d == q -> 110
                t.equals(q, true) || d.equals(q, true) -> 95
                t.contains(q) || d.contains(q) -> 82
                t.contains(q, true) || d.contains(q, true) -> 72
                else -> 0
            }

            val cov = maxOf(coverageCount(t), coverageCount(d))
            if (cov > 0) s += cov * 14
            if (cov >= allTokens.size && allTokens.isNotEmpty()) s += 10
            s += inOrderBonus(t.ifEmpty { d })


            if (s > 0 && (clickable || focusable)) s += 3
            if (s > 0 && el.attr("resource-id").isNotBlank()) s += 2

            s += (t.length.takeIf { it > 0 } ?: d.length).coerceAtMost(40) / 4
            return s
        }

        fun elToIndexedXPath(e: Element): String {
            val parts = mutableListOf<String>()
            var cur: Element? = e
            while (cur != null && cur.tagName() == "node") {
                val parent = cur.parent()
                val siblings = parent?.children()?.filter { it.tagName() == "node" }.orEmpty()
                val idx = siblings.indexOf(cur) + 1
                val cls = cur.attr("class")
                parts += if (cls.isNotBlank()) "*[@class='${cls}'][$idx]" else "node[$idx]"
                cur = parent
            }
            return "//" + parts.asReversed().joinToString("/")
        }

        val nodes = doc.select("node[text], node[content-desc], node[clickable=true], node[focusable=true]")
        val scored = buildList {
            for (q in queries) for (el in nodes) {
                val s = scoreFor(el, q)
                if (s > 0) add(Scored(el, s))
            }
        }
        if (scored.isEmpty()) return null
        val best = scored.maxBy { it.score }
        val xp = elToIndexedXPath(best.el)
        logger("rebuilt xpath (smart) => $xp  [score=${best.score}]")
        return runCatching { driver.findElement(AppiumBy.xpath(xp)); xp }.getOrNull()
    }

    fun resolveForInputAdvanced(targetHint: String, logger: (String) -> Unit = log): Locator {
        val h = targetHint.trim()
        val hLower = h.lowercase()

        fun tryQuery(strategy: Strategy, label: String, by: () -> By): Locator? = try {
            logger("try $label")
            driver.findElement(by())
            val v = by().toString().substringAfter(": ").ifBlank { by().toString() }
            logger("…found via $label -> $v")
            Locator(strategy, v)
        } catch (_: Exception) { null }

        // Heuristic id patterns
        val syn = when {
            "user" in hLower -> "(?i).*(user(name)?|login|email).*"
            "pass" in hLower -> "(?i).*(pass(word)?|pwd).*"
            "mobile" in hLower || "phone" in hLower -> "(?i).*(mobile|phone|msisdn).*"
            else -> "(?i).*${Regex.escape(hLower)}.*"
        }

        tryQuery(Strategy.UIAUTOMATOR, """resourceIdMatches("$syn")""") {
            AppiumBy.androidUIAutomator("""new UiSelector().resourceIdMatches("$syn")""")
        }?.let { return it }

        val xml = driver.pageSource
        val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())

        val labels = doc.select(
            "node[text~=(?i).*${Regex.escape(hLower)}.*], node[content-desc~=(?i).*${Regex.escape(hLower)}.*]"
        )
        logger("labels matched: ${labels.size}")

        fun isEditable(e: Element): Boolean {
            val cls = e.attr("class")
            val focusable = e.attr("focusable") == "true"
            val clickable = e.attr("clickable") == "true"
            val editClass = cls.contains("EditText", true)
                    || cls.contains("TextInputEditText", true)
                    || cls.contains("AutoCompleteTextView", true)
                    || (cls.contains("Custom", true) && (focusable || clickable))
            return editClass || (focusable && clickable)
        }

        fun elementToXPath(e: Element): String {
            val parts = mutableListOf<String>()
            var cur: Element? = e
            while (cur != null && cur.tagName() == "node") {
                val parent = cur.parent()
                val siblings = parent?.children()?.filter { it.tagName() == "node" }.orEmpty()
                val idx = siblings.indexOf(cur) + 1
                val cls = cur.attr("class")
                parts += if (cls.isNotBlank()) "*[@class='${cls}'][$idx]" else "node[$idx]"
                cur = parent
            }
            return "//" + parts.asReversed().joinToString("/")
        }

        // Label → sibling editable
        for (label in labels) {
            label.siblingElements().forEach { sib ->
                val cand = sib.selectFirst("node[class~=.*]") ?: sib
                if (isEditable(cand)) {
                    val xp = elementToXPath(cand)
                    logger("label→sibling editable: $xp")
                    return Locator(Strategy.XPATH, xp)
                }
            }
            // Label → ancestor → descendant editable
            var anc: Element? = label.parent()
            repeat(4) {
                if (anc == null) return@repeat
                val edit = anc!!.selectFirst(
                    "node[class~=.*(EditText|AutoCompleteTextView|TextInputEditText|Custom).*]"
                )
                if (edit != null) {
                    val xp = elementToXPath(edit)
                    logger("label→ancestor→descendant editable: $xp")
                    return Locator(Strategy.XPATH, xp)
                }
                anc = anc!!.parent()
            }
        }

        // Fallback: first visible/focusable editable
        val anyEdit = doc.select(
            "node[class~=.*(EditText|AutoCompleteTextView|TextInputEditText).*], node[focusable=true]"
        ).firstOrNull()
        if (anyEdit != null) {
            val xp = elementToXPath(anyEdit)
            logger("fallback editable: $xp")
            return Locator(Strategy.XPATH, xp)
        }

        logger("ultimate fallback: //*[contains(@class,'EditText')][1]")
        return Locator(Strategy.XPATH, "(//*[contains(@class,'EditText')])[1]")
    }

    fun isPresentQuick(
        targetHint: String,
        timeoutMs: Long = 2_500,
        logger: (String) -> Unit = log
    ): Locator? {
        logger("isPresentQuick(\"$targetHint\", $timeoutMs ms)")
        val end = System.currentTimeMillis() + timeoutMs
        var lastErr: Throwable? = null
        while (System.currentTimeMillis() < end) {
            waitForStableUi()
            runCatching {
                val loc = resolve(targetHint, logger)
                driver.findElement(loc.toBy())
                logger("✓ present(quick) via ${loc.strategy} -> ${loc.value}")
                return loc
            }.onFailure { lastErr = it }
            // one fast rebuild try
            runCatching {
                rebuildXPathFromDump(targetHint, logger)?.let { xp ->
                    val loc = Locator(Strategy.XPATH, xp)
                    driver.findElement(loc.toBy())
                    logger("✓ present(quick) via rebuilt xpath")
                    return loc
                }
            }.onFailure { lastErr = it }
            Thread.sleep(150)
        }
        logger("…not present(quick): ${lastErr?.message ?: "no match"}")
        return null
    }

    fun resolveCheckbox(targetHint: String?, nth: Int = 1, logger: (String) -> Unit = log): Locator {
        val safeNth = nth.coerceAtLeast(1)

        fun tryByXPath(xp: String, label: String): Locator? = try {
            logger("try $label => $xp")
            driver.findElement(AppiumBy.xpath(xp))
            Locator(Strategy.XPATH, xp)
        } catch (_: Exception) { null }

        if (!targetHint.isNullOrBlank()) {
            val toks = tokensOrdered(targetHint)
            val lookahead = toks.joinToString("") { "(?=.*${Regex.escape(it)})" }
            val rx = if (lookahead.isBlank()) "(?i).*" else "(?i)$lookahead.*"

            val xml = driver.pageSource
            val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())

            val labels = doc.select("node[text~=$rx], node[content-desc~=$rx]")
            logger("checkbox label matches: ${labels.size}")

            fun elementToXPath(e: Element): String {
                val parts = mutableListOf<String>()
                var cur: Element? = e
                while (cur != null && cur.tagName() == "node") {
                    val parent = cur.parent()
                    val siblings = parent?.children()?.filter { it.tagName() == "node" }.orEmpty()
                    val idx = siblings.indexOf(cur) + 1
                    val cls = cur.attr("class")
                    parts += if (cls.isNotBlank()) "*[@class='${cls}'][$idx]" else "node[$idx]"
                    cur = parent
                }
                return "//" + parts.asReversed().joinToString("/")
            }

            // label → sibling/descendant with checkable=true; otherwise ancestor → descendant
            for (lab in labels) {
                (lab.siblingElements() + lab.allElements).forEach { cand ->
                    val chk = cand.selectFirst("node[checkable=true]") ?: return@forEach
                    val xp = elementToXPath(chk)
                    tryByXPath(xp, "label-adjacent checkable")?.let { return it }
                }
                var anc: Element? = lab.parent()
                repeat(4) {
                    if (anc == null) return@repeat
                    val chk = anc.selectFirst("node[checkable=true]")
                    if (chk != null) {
                        val xp = elementToXPath(chk)
                        tryByXPath(xp, "ancestor-descendant checkable")?.let { return it }
                    }
                    anc = anc.parent()
                }
            }
        }

        val xpNth = "(//*[@checkable='true'])[$safeNth]"
        tryByXPath(xpNth, "nth checkable")?.let { return it }

        val xpClass = "(//*[@class and (contains(@class,'CheckBox') or contains(@class,'Switch') or contains(@class,'CompoundButton'))])[$safeNth]"
        tryByXPath(xpClass, "nth class-based checkable")?.let { return it }

        logger("fallback nth checkable (broad) -> $xpNth")
        return Locator(Strategy.XPATH, xpNth)
    }

    private fun resolveRelativeToAnchor(anchorText: String, targetText: String): By {
        // normalize text to lower
        val lowerAnchor = anchorText.lowercase()
        val lowerTarget = targetText.lowercase()

        // 1. find the anchor node (FROM or TO)
        val anchorXpath = "//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='$lowerAnchor']"

        // 2. now find the first target below that anchor
        return AppiumBy.xpath(
            "($anchorXpath/following::*" +
                    "[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='$lowerTarget'])[1]"
        )
    }


}
