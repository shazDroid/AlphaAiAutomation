package agent

import io.appium.java_client.AppiumBy
import io.appium.java_client.android.AndroidDriver
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebElement
import org.openqa.selenium.remote.RemoteWebElement


class AgentRunner(
    private val driver: AndroidDriver,
    private val resolver: LocatorResolver,
    private val store: SnapshotStore
) {
    private val DEFAULT_WAIT_TEXT_TIMEOUT_MS = 45_000L
    private val DEFAULT_TAP_TIMEOUT_MS = 15_000L
    private val STRICT_XPATH_ONLY = true

    fun run(
        plan: ActionPlan,
        onStep: (Snapshot) -> Unit = {},
        onLog: (String) -> Unit = {},
        onStatus: (String) -> Unit = {}
    ): List<Snapshot> {

        runCatching {
            val js = driver as JavascriptExecutor
            val inner: HashMap<String, Any> = hashMapOf("enforceXPath1" to true)
            val payload: HashMap<String, Any> = hashMapOf("settings" to inner)
            js.executeScript("mobile: setSettings", payload)
        }.onFailure { _: Throwable ->

        }


        val out = mutableListOf<Snapshot>()
        onLog("Plan started: \"${plan.title}\" (${plan.steps.size} steps)")
        var pc = 0
        val steps = plan.steps

        fun jumpToLabelOrThrow(name: String): Int {
            val idx = steps.indexOfFirst { it.type == StepType.LABEL && it.targetHint == name }
            require(idx >= 0) { "GOTO/IF target label not found: $name" }
            return idx
        }

        while (pc in steps.indices) {
            val step = steps[pc]
            onStatus("Step ${step.index}/${steps.size}: ${step.type} ${step.targetHint ?: ""}")
            onLog("➡️  ${step.index} ${step.type} target='${step.targetHint}' value='${step.value}'")

            var ok = true
            var chosen: Locator? = null
            var notes: String? = null

            try {
                when (step.type) {
                    StepType.LAUNCH_APP -> {
                        val pkg = step.targetHint ?: error("Missing package for LAUNCH_APP")
                        val currentPkg = runCatching { driver.currentPackage }.getOrNull()
                        val alreadyOk = !currentPkg.isNullOrBlank() && currentPkg == pkg
                        if (alreadyOk) {
                            onLog("✓ app already running in foreground: $currentPkg (skip activateApp)")
                        } else {
                            onStatus("Launching $pkg")
                            val activated = runCatching { driver.activateApp(pkg) }
                                .onSuccess { onLog("✓ app activated: $pkg") }
                                .isSuccess
                            if (!activated) {
                                onLog("! activateApp failed, attempting monkey fallback")
                                runCatching {
                                    (driver as JavascriptExecutor).executeScript(
                                        "mobile: shell",
                                        mapOf(
                                            "command" to "monkey",
                                            "args" to listOf("-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")
                                        )
                                    )
                                    onLog("✓ monkey launch fallback executed for $pkg")
                                }.onFailure { e ->
                                    throw IllegalStateException("LAUNCH_APP failed for '$pkg': ${e.message}", e)
                                }
                            }
                        }
                        store.capture(step.index, step.type, step.targetHint, null, true, "LAUNCH_APP")
                    }

                    StepType.INPUT_TEXT -> {
                        val th    = step.targetHint ?: error("Missing target for INPUT_TEXT")
                        val value = step.value      ?: error("Missing value for INPUT_TEXT")

                        onStatus("Typing into \"$th\"")
                        chosen = withRetry(attempts = 3, delayMs = 700,
                            onError = { n, e -> onLog("  retry($n) INPUT_TEXT: ${e.message}") }) {
                            resolver.waitForStableUi()

                            val labelText = th.trim()

                            val result = findEditTextForLabel(driver, labelText, th) { msg -> onLog("  $msg") }
                            val xp    = result.first
                            val edit  = result.second

                            runCatching { edit.click() }.onFailure {}
                            edit.clear()
                            edit.sendKeys(value)

                            Locator(Strategy.XPATH, xp)
                        }
                        onLog("✓ input done")
                    }



                    StepType.TAP, StepType.CHECK -> {
                        val th = step.targetHint ?: error("Missing target for ${step.type}")

                        // TAP: keep your package-name guard
                        if (step.type == StepType.TAP) {
                            val pkgLike = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+\$")
                            if (pkgLike.matches(th)) {
                                onLog("↪︎ skip TAP on package name '$th' (invalid target)")
                                onStatus("Skipping invalid tap on package")
                                store.capture(step.index, step.type, step.targetHint, null, true, "SKIPPED_INVALID_TARGET")
                                pc += 1
                                continue
                            }
                        }

                        onStatus("${if (step.type == StepType.TAP) "Tapping" else "Checking"} \"$th\"")

                        val original: Locator = if (step.type == StepType.CHECK) {
                            val nth = step.meta["nth"]?.toIntOrNull() ?: ordinalToIndex(th) ?: 1

                            val xpCheckable = "(//*[@checkable='true'])[$nth]"
                            runCatching { driver.findElement(AppiumBy.xpath(xpCheckable)) }.getOrNull()?.let {
                                onLog("  ✓ ordinal checkable[$nth] found")
                                Locator(Strategy.XPATH, xpCheckable)
                            } ?: run {
                                val xpUnion = "((//android.widget.CheckBox) | (//android.widget.Switch) | (//*[contains(@resource-id,'check') or contains(@resource-id,'tick') or contains(@content-desc,'check')]))[$nth]"
                                runCatching { driver.findElement(AppiumBy.xpath(xpUnion)) }.getOrNull()?.let {
                                    onLog("  ✓ union checkable[$nth] found")
                                    Locator(Strategy.XPATH, xpUnion)
                                } ?: run {
                                    onLog("  … index probes failed, fallback to resolver.resolveCheckbox(nth=$nth)")
                                    resolver.resolveCheckbox(null, nth) { msg -> onLog("  $msg") }
                                }
                            }
                        } else {
                            resolver.waitForElementPresent(
                                targetHint = th,
                                timeoutMs = DEFAULT_TAP_TIMEOUT_MS,
                                clickIfFound = false
                            ) { msg -> onLog("  $msg") }
                        }

                        val validated = preMaterializeValidatedXPath(driver, original)
                            ?: if (STRICT_XPATH_ONLY) {
                                throw IllegalStateException("Could not pre-materialize validated XPath for ${step.type} '$th'")
                            } else null

                        val by = validated?.let { AppiumBy.xpath(it.xpath) } ?: original.toBy()
                        val el = driver.findElement(by)

                        if (step.type == StepType.CHECK) {
                            val desire = step.value?.trim()?.lowercase()
                            val isChecked = (runCatching { el.getAttribute("checked") }.getOrNull() ?: "false") == "true"
                            val want = when (desire) {
                                "on","true","checked","tick","select" -> true
                                "off","false","unchecked","untick","deselect" -> false
                                else -> !isChecked
                            }
                            if (isChecked != want) el.click()
                            onLog("✓ checkbox state -> ${if (want) "ON" else "OFF"}")
                        } else {
                            el.click()
                            onLog("✓ tapped")
                        }

                        chosen = validated?.toLocatorWith(original) ?: original
                    }



                    StepType.WAIT_TEXT -> {
                        val q = (step.targetHint ?: step.value) ?: error("Missing query for WAIT_TEXT")
                        val timeout = step.meta["timeoutMs"]?.toLongOrNull() ?: DEFAULT_WAIT_TEXT_TIMEOUT_MS
                        onStatus("Waiting for \"$q\" (timeout ${timeout}ms)")
                        var lastErr: Throwable? = null
                        var recorded: Locator? = null

                        repeat(2) { r ->
                            try {
                                resolver.waitForStableUi()
                                val original = resolver.waitForElementPresent(
                                    targetHint = q,
                                    timeoutMs = timeout,
                                    clickIfFound = false
                                ) { msg -> onLog("  $msg") }

                                val validated = preMaterializeValidatedXPath(driver, original)
                                recorded = validated?.toLocatorWith(original) ?: original
                                onLog("✓ visible")
                                return@repeat
                            } catch (e: Throwable) {
                                lastErr = e
                                onLog("  retry(${r + 1}) WAIT_TEXT: ${e.message}")
                                Thread.sleep(400)
                            }
                        }
                        if (recorded == null) throw IllegalStateException("WAIT_TEXT timeout: $q${lastErr?.let { " (${it.message})" } ?: ""}")
                        chosen = recorded
                    }

                    StepType.SCROLL_TO -> {
                        val th = step.targetHint ?: error("Missing target for SCROLL_TO")
                        onStatus("Scrolling to \"$th\"")
                        resolver.scrollToText(th)
                        onLog("✓ scrolled")
                    }

                    StepType.WAIT_OTP -> {
                        val digits = step.value?.toIntOrNull() ?: 6
                        onStatus("Waiting for OTP ($digits digits)")
                        resolver.waitForText("length:$digits", timeoutMs = 30_000)
                        onLog("✓ OTP detected")
                    }

                    StepType.ASSERT_TEXT -> {
                        val th = (step.targetHint ?: step.value) ?: error("Missing target for ASSERT_TEXT")
                        onStatus("Asserting \"$th\" is visible")
                        val original = resolver.waitForElementPresent(
                            targetHint = th,
                            timeoutMs = 12_000,
                            clickIfFound = false
                        ) { msg -> onLog("  $msg") }
                        val validated = preMaterializeValidatedXPath(driver, original)
                        chosen = validated?.toLocatorWith(original) ?: original
                        onLog("✓ assertion passed")
                    }

                    StepType.BACK -> {
                        onStatus("Navigating back")
                        driver.navigate().back()
                        onLog("✓ back")
                        store.capture(step.index, step.type, step.targetHint, null, true, null)
                    }

                    StepType.SLEEP -> {
                        val ms = (step.value ?: "500").toLong()
                        onStatus("Sleeping ${ms}ms")
                        Thread.sleep(ms)
                        onLog("✓ wake")
                        store.capture(step.index, step.type, step.targetHint, null, true, null)
                    }

                    StepType.LABEL -> {
                        onLog("📍 label: ${step.targetHint}")
                        store.capture(step.index, step.type, step.targetHint, null, true, null)
                    }

                    StepType.GOTO -> {
                        val lbl = step.meta["label"] ?: step.targetHint ?: error("Missing label for GOTO")
                        onLog("↩︎ goto '$lbl'")
                        pc = jumpToLabelOrThrow(lbl)
                        val snap = store.capture(step.index, step.type, step.targetHint, null, true, null)
                        onStep(snap); out += snap
                        continue
                    }

                    StepType.IF_VISIBLE -> {
                        val q = step.targetHint ?: error("Missing query for IF_VISIBLE")
                        val tLbl = step.meta["then"] ?: error("Missing meta.then for IF_VISIBLE")
                        val fLbl = step.meta["else"] ?: error("Missing meta.else for IF_VISIBLE")
                        val tout = step.meta["timeoutMs"]?.toLongOrNull() ?: 2500L
                        onStatus("IF_VISIBLE \"$q\" ? then '$tLbl' : '$fLbl'")
                        val hit = resolver.isPresentQuick(q, timeoutMs = tout) { msg -> onLog("  $msg") } != null
                        onLog(if (hit) "✓ IF true → $tLbl" else "✓ IF false → $fLbl")
                        pc = jumpToLabelOrThrow(if (hit) tLbl else fLbl)
                        val snap = store.capture(step.index, step.type, step.targetHint, null, true, null)
                        onStep(snap); out += snap
                        continue
                    }

                    StepType.SLIDE -> {
                        val th = step.targetHint ?: error("Missing target for SLIDE")
                        onStatus("Sliding \"$th\"")
                        withRetry(
                            attempts = 2,
                            delayMs = 500,
                            onError = { n, e -> onLog("  retry($n) SLIDE: ${e.message}") }) {
                            resolver.waitForStableUi()
                            InputEngine.slideRightByHint(driver, resolver, th) { msg -> onLog("  $msg") }
                        }
                        onLog("✓ slid")
                    }
                }
            } catch (e: Exception) {
                ok = false
                notes = e.message
                onStatus("❌ ${step.type} failed: ${e.message}")
                onLog("❌ error: $e")
            }

            val snap = store.capture(step.index, step.type, step.targetHint, chosen, ok, notes)
            out += snap
            onStep(snap)
            if (!ok) break
            pc += 1
        }

        onLog("Plan completed. Success ${out.count { it.success }}/${out.size}")
        return out
    }

    // ---------- XPath pre-materialization & validation ----------

    private data class ValidatedXPath(val xpath: String, val element: WebElement)

    private fun preMaterializeValidatedXPath(
        driver: AndroidDriver,
        original: Locator
    ): ValidatedXPath? {
        val live = driver.findElement(original.toBy())
        val candidates = buildXPathCandidates(live)

        for (xp in candidates) {
            val matches = driver.findElements(AppiumBy.xpath(xp))
            if (matches.size == 1 && isSameElement(matches[0], live)) {
                return ValidatedXPath(xp, matches[0])
            }
        }
        for (xp in candidates) {
            val matches = driver.findElements(AppiumBy.xpath(xp))
            val same = matches.firstOrNull { isSameElement(it, live) }
            if (same != null) return ValidatedXPath(xp, same)
        }
        return null
    }

    private fun buildXPathCandidates(el: WebElement): List<String> {
        val resId       = el.attrSafe("resource-id")
        val contentDesc = el.attrSafe("content-desc").ifEmpty { el.attrSafe("contentDescription") }
        val txt         = (runCatching { el.text }.getOrNull() ?: el.attrSafe("text")).trim()
        val clazz       = el.attrSafe("className").ifEmpty { el.attrSafe("class") }

        val out = mutableListOf<String>()
        if (resId.isNotEmpty())       out += "//*[@resource-id=${xpathLiteral(resId)}]"
        if (contentDesc.isNotEmpty()) out += "//*[@content-desc=${xpathLiteral(contentDesc)}]"
        if (txt.isNotEmpty())         out += "//*[@text=${xpathLiteral(txt)}]"
        if (clazz.isNotEmpty())       out += "//$clazz"
        return out.distinct()
    }

    private fun isSameElement(a: WebElement, b: WebElement): Boolean {
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


    private fun WebElement.attrSafe(name: String): String {
        val v = runCatching { this.getAttribute(name) }.getOrNull() ?: return ""
        val s = v.trim()
        return if (s.equals("null", ignoreCase = true) || s.equals("none", ignoreCase = true)) "" else s
    }

    private fun xpathLiteral(s: String): String = when {
        '\'' !in s -> "'$s'"
        '"'  !in s -> "\"$s\""
        else -> "concat('${s.replace("'", "',\"'\",'")}')"
    }

    private fun ValidatedXPath.toLocatorWith(original: Locator): Locator =
        Locator(
            strategy = Strategy.XPATH,
            value = this.xpath,
            alternatives = listOf(original.strategy to original.value)
        )

    private fun findEditTextForLabel(
        driver: AndroidDriver,
        labelText: String,
        hint: String,
        log: (String) -> Unit
    ): Pair<String, WebElement> {
        val xpContainer =
            "(//*[@resource-id and .//*[@text=${xpathLiteral(labelText)}]])[1]"
        driver.findElements(AppiumBy.xpath(xpContainer)).firstOrNull()?.let { _ ->
            val xp = "($xpContainer//android.widget.EditText)[1]"
            driver.findElements(AppiumBy.xpath(xp)).firstOrNull()?.let { ed ->
                log("A) using container→EditText")
                return xp to ed
            } ?: run { log("A) container found but no EditText inside") }
        } ?: log("A) no container with @resource-id containing label")

        val xpFollowing =
            "//*[@text=${xpathLiteral(labelText)}]/following::android.widget.EditText[1]"
        driver.findElements(AppiumBy.xpath(xpFollowing)).firstOrNull()?.let { ed ->
            log("B) using label-following EditText")
            return xpFollowing to ed
        } ?: log("B) no following EditText after label")

        val idx = if (hint.contains("pass", ignoreCase = true)) 2 else 1
        val xpIndex = "(//android.widget.EditText)[$idx]"
        driver.findElements(AppiumBy.xpath(xpIndex)).firstOrNull()?.let { ed ->
            log("C) using index fallback: $idx")
            return xpIndex to ed
        }

        throw IllegalStateException("EditText not found for label \"$labelText\"")
    }

    private fun ordinalToIndex(hint: String?): Int? {
        if (hint == null) return null
        val s = hint.lowercase().trim()
        return when {
            Regex("""\b(first|1st)\b""").containsMatchIn(s)  -> 1
            Regex("""\b(second|2nd)\b""").containsMatchIn(s) -> 2
            Regex("""\b(third|3rd)\b""").containsMatchIn(s)  -> 3
            else -> Regex("""\b(\d+)(?:st|nd|rd|th)?\b""").find(s)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
    }

}
