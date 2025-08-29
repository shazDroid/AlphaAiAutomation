package agent

import adb.UiDumpParser.findEditTextForLabel
import adb.UiDumpParser.xpathLiteral
import agent.candidates.UICandidate
import agent.candidates.extractCandidatesForHint
import agent.llm.LlmDisambiguator
import agent.llm.MultiAgentSelector
import agent.semantic.SemanticReranker
import agent.vision.DekiYoloClient
import agent.vision.ScreenVision
import agent.vision.VisionResult
import io.appium.java_client.AppiumBy
import io.appium.java_client.android.AndroidDriver
import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.OutputType
import org.openqa.selenium.WebElement
import org.openqa.selenium.remote.RemoteWebElement

class AgentRunner(
    private val driver: AndroidDriver,
    private val resolver: LocatorResolver,
    private val store: SnapshotStore,
    private val llmDisambiguator: LlmDisambiguator? = null,
    private val semanticReranker: SemanticReranker? = null,
    private val selector: MultiAgentSelector? = null,
    private val deki: DekiYoloClient? = null
) {
    private val DEFAULT_WAIT_TEXT_TIMEOUT_MS = 45_000L
    private val DEFAULT_TAP_TIMEOUT_MS = 20_000L
    private val DIALOG_POLL_MS = 140L
    private val DIALOG_WINDOW_AFTER_STEP_MS = 2400L

    private var activeScope: String? = null
    private var lastTapY: Int? = null

    private var lastVisionHash: Int? = null
    private var lastVision: VisionResult? = null
    private var lastVisionScope: String? = null

    private var scopeTtlSteps: Int = 0
    private fun setScope(s: String?) {
        activeScope = s?.lowercase(); scopeTtlSteps = if (s == null) 0 else 3
    }

    private fun tickScopeTtl() {
        if (scopeTtlSteps > 0) scopeTtlSteps--; if (scopeTtlSteps == 0) activeScope = null
    }


    fun run(
        plan: ActionPlan,
        onStep: (Snapshot) -> Unit = {},
        onLog: (String) -> Unit = {},
        onStatus: (String) -> Unit = {},
        stopSignal: () -> Boolean = { false }
    ): List<Snapshot> {

        runCatching {
            val js = driver as JavascriptExecutor
            js.executeScript("mobile: setSettings", mapOf("settings" to mapOf("enforceXPath1" to true)))
        }

        fun ensureNotStopped() { if (stopSignal()) throw InterruptedException("STOP_REQUESTED") }

        val out = mutableListOf<Snapshot>()
        onLog("Plan started: \"${plan.title}\" (${plan.steps.size} steps)")
        onLog(if (deki == null) "vision:disabled" else "vision:enabled")
        var pc = 0
        val steps = plan.steps
        val sel = selector ?: MultiAgentSelector(driver, llmDisambiguator)

        fun jumpToLabelOrThrow(name: String): Int {
            val idx = steps.indexOfFirst { it.type == StepType.LABEL && it.targetHint == name }
            require(idx >= 0) { "GOTO/IF target label not found: $name" }
            return idx
        }

        while (pc in steps.indices) {
            if (stopSignal()) {
                onStatus("⏹️ Stopped by user")
                onLog("⏹️ Stopped by user")
                break
            }

            val step = steps[pc]
            onStatus("Step ${step.index}/${steps.size}: ${step.type} ${step.targetHint ?: ""}")
            onLog("➡️  ${step.index} ${step.type} target='${step.targetHint}' value='${step.value}'")

            var ok = true
            var chosen: Locator? = null
            var notes: String? = null

            try {
                ensureNotStopped()
                when (step.type) {

                    StepType.LAUNCH_APP -> {
                        val pkg = step.targetHint ?: error("Missing package for LAUNCH_APP")
                        val currentPkg = runCatching { driver.currentPackage }.getOrNull()
                        if (!currentPkg.isNullOrBlank() && currentPkg == pkg) {
                            onLog("✓ app already running in foreground: $currentPkg")
                        } else {
                            onStatus("Launching $pkg")
                            val activated = runCatching { driver.activateApp(pkg) }.isSuccess
                            if (!activated) {
                                runCatching {
                                    (driver as JavascriptExecutor).executeScript(
                                        "mobile: shell",
                                        mapOf("command" to "monkey", "args" to listOf("-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"))
                                    )
                                }.getOrElse { e -> throw IllegalStateException("LAUNCH_APP failed for '$pkg': ${e.message}", e) }
                            }
                        }
                        store.capture(step.index, step.type, step.targetHint, null, true, "LAUNCH_APP")
                        handleDialogWithPolling(step.index, onLog, onStatus, DIALOG_WINDOW_AFTER_STEP_MS)
                    }

                    StepType.INPUT_TEXT -> {
                        runCatching {
                            val v = analyzeWithVision("scope.check", onLog)
                            val hasFromTo = v?.elements?.any {
                                it.text.equals("from", true) || it.text.equals(
                                    "to",
                                    true
                                )
                            } == true ||
                                    driver.findElements(AppiumBy.xpath("//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='from' or translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='to']"))
                                        .isNotEmpty()
                            if (!hasFromTo) activeScope = null
                        }.onFailure { /* ignore */ }


                        val th = step.targetHint ?: error("Missing target for INPUT_TEXT")
                        val value = step.value ?: error("Missing value for INPUT_TEXT")
                        onStatus("Typing into \"$th\"")

                        chosen = withRetry<Locator>(
                            attempts = 3, delayMs = 650,
                            onError = { n, e -> onLog("  retry($n) INPUT_TEXT: ${e.message}") }) {
                            ensureNotStopped()
                            resolver.waitForStableUi()

                            val (xp, edit) = findEditTextForLabel(driver, th.trim(), th) { msg -> onLog("  $msg") }
                            ensureNotStopped()
                            runCatching { edit.click() }
                            edit.clear()
                            edit.sendKeys(value)

                            Locator(Strategy.XPATH, xp)
                        }

                        onLog("✓ input done")
                        handleDialogWithPolling(step.index, onLog, onStatus, DIALOG_WINDOW_AFTER_STEP_MS)
                    }

                    StepType.TAP -> {
                        runCatching {
                            val v = analyzeWithVision("scope.check", onLog)
                            val hasFromTo = v?.elements?.any {
                                it.text.equals("from", true) || it.text.equals(
                                    "to",
                                    true
                                )
                            } == true ||
                                    driver.findElements(AppiumBy.xpath("//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='from' or translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='to']"))
                                        .isNotEmpty()
                            if (!hasFromTo) activeScope = null
                        }.onFailure { /* ignore */ }


                        val rawHint = step.targetHint ?: error("Missing target for TAP")
                        val th = rawHint.trim()
                        val timeout = step.meta["timeoutMs"]?.toLongOrNull() ?: DEFAULT_TAP_TIMEOUT_MS
                        val preferredSection = step.meta["section"] ?: parseSectionFromHint(th)
                        val effectiveSection = preferredSection ?: activeScope
                        val desiredToggle = parseDesiredToggle(th)
                        onStatus("Tapping \"$th\"")
                        ensureNotStopped()

                        // 1) Vision-first
                        val handledByVision: Boolean = run {
                            var vres = analyzeWithVisionFast("tap.pre", onLog, effectiveSection)
                            val labelFound =
                                vres?.elements?.any { it.text?.contains(th, ignoreCase = true) == true } == true
                            if (!labelFound) {
                                onLog("vision:tap.pre upgrade_to_ocr")
                                vres = analyzeWithVisionSlowForText("tap.pre.ocr", onLog, effectiveSection)
                            }

                            logVisionSummary("tap.pre", vres!!, preferredSection, onLog)

                            val vr = ScreenVision.findToggleForLabel(
                                driver, vres, th, preferredSection
                            ) ?: return@run false

                            val before = safePageSourceHash()
                            runCatching { vr.second.click() }
                            chosen = vr.first

                            lastTapY = runCatching { vr.second.rect.let { it.y + it.height / 2 } }.getOrNull()
                            activeScope = determineScopeByY(lastTapY, vres) ?: activeScope
                            onLog("scope:update after tap → ${activeScope ?: "-"}")

                            val dialogHandled = runCatching {
                                handleDialogWithPolling(step.index, onLog, onStatus, 1600L)
                            }.getOrDefault(false)
                            val uiChanged = dialogHandled || waitUiChangedSince(before, 1800L)
                            if (uiChanged) {
                                onLog("vision:tap.success xpath=${vr.first.value}")
                                val snap = store.capture(step.index, step.type, step.targetHint, chosen, true, null)
                                out += snap
                                onStep(snap)
                                pc += 1
                                true
                            } else false
                        }
                        if (handledByVision) continue

                        // 2) Legacy toggle-by-label in UI tree
                        val toggleHit = tryToggleByLabel(th, preferredSection, desiredToggle, onLog)
                        if (toggleHit.first) {
                            chosen = toggleHit.second
                            onLog("✓ toggled (legacy)")
                            handleDialogWithPolling(step.index, onLog, onStatus, 1400L)
                            val snap = store.capture(step.index, step.type, step.targetHint, chosen, true, null)
                            out += snap
                            onStep(snap)
                            pc += 1
                            continue
                        }

                        // 3) Candidate search + exact + LLM + semantic
                        val deadline = System.currentTimeMillis() + timeout
                        var changed = false
                        var tappedLocator: Locator? = null
                        var lastScanError: String? = null

                        while (System.currentTimeMillis() < deadline && !changed) {
                            ensureNotStopped()
                            resolver.waitForStableUi()
                            if (waitWhileBusy(2_500L)) { continue }
                            val before = safePageSourceHash()

                            val all0 = runCatching { extractCandidatesForHint(driver, th, limit = 80) }.getOrElse {
                                lastScanError = it.message; emptyList()
                            }
                            if (all0.isEmpty()) { Thread.sleep(300); continue }

                            val vres = analyzeWithVisionFast("tap.loop", onLog, effectiveSection)

                            if (vres != null && effectiveSection != null) {
                                onLog("vision:restrict section=$effectiveSection (before=${all0.size})")
                            }
                            val all = if (effectiveSection != null && vres != null)
                                ScreenVision.restrictCandidatesToSection(
                                    driver, all0, vres, effectiveSection, getXpath = { it.xpath }
                                )
                            else
                                applySectionScope(all0, effectiveSection)


                            fun norm(s: String?) = (s ?: "").lowercase()
                                .replace("&", "and")
                                .replace(Regex("[\\p{Punct}]"), " ")
                                .replace(Regex("\\s+"), " ")
                                .trim()

                            val exact = all.filter { norm(it.label) == norm(th) }.toMutableList()
                            val ordered = mutableListOf<UICandidate>()
                            if (exact.isNotEmpty()) {
                                ordered += preferByRoleAndPosition(exact)
                                onLog("pick:exact ${ordered.first().label}")
                            }

                            if (ordered.isEmpty()) {
                                val visionHint = vres?.let { buildVisionHint(it, effectiveSection) } ?: "-"
                                val instructionForSelector = buildString {
                                    append(th)
                                    if (effectiveSection != null) append("\nACTIVE_SCOPE: ").append(effectiveSection.uppercase())
                                    append("\nVISION_TEXTS: ").append(visionHint)
                                }
                                val pick = sel.select(instructionForSelector, all)
                                pick.candidateId?.let { cid ->
                                    all.firstOrNull { it.id == cid }?.let { choice ->
                                        onLog("llm_pick=${choice.id} label='${choice.label}' scope=${pick.scope} op=${pick.op}")
                                        ordered += choice
                                    }
                                }
                            }

                            if (ordered.isEmpty() && semanticReranker != null) {
                                val semTop = runCatching { semanticReranker?.rerank(th, all)?.take(10) }
                                    .getOrDefault(emptyList())
                                if (!semTop.isNullOrEmpty()) ordered += semTop
                            }

                            if (ordered.isEmpty()) {
                                val near = all.filter { nearEquals(th, it.label) }
                                if (near.isNotEmpty()) ordered += preferByRolePosAndAnchor(near, lastTapY)
                            }
                            if (ordered.isEmpty()) ordered += preferByRolePosAndAnchor(all, lastTapY)

                            fun tryTapCandidate(cand: UICandidate): Boolean {
                                val loc = Locator(Strategy.XPATH, cand.xpath)
                                val validated = preMaterializeValidatedXPath(driver, loc)
                                val by = validated?.let { AppiumBy.xpath(it.xpath) } ?: AppiumBy.xpath(loc.value)
                                val el = runCatching { driver.findElement(by) }.getOrNull() ?: return false
                                el.click()

                                // after a successful vision tap:
                                setScope(determineScopeByY(lastTapY, vres) ?: activeScope)
                                onLog("scope:update after tap → ${activeScope ?: "-"}")


                                tappedLocator = validated?.toLocatorWith(loc) ?: loc
                                val dialogHandled = runCatching { handleDialogWithPolling(step.index, onLog, onStatus, 1600L) }.getOrDefault(false)
                                val uiChanged = dialogHandled || waitUiChangedSince(before, 1800L)
                                return uiChanged
                            }

                            for (cand in ordered.distinctBy { it.id }) {
                                if (tryTapCandidate(cand)) { changed = true; break }
                            }
                            if (!changed) Thread.sleep(280)
                        }

                        if (!changed) {
                            onStatus("""ACTION_REQUIRED::Tap "$th" on the device""")
                            onLog("  waiting up to 12s for manual tap…")
                            val manual = waitUiChangedSince(safePageSourceHash(), 12_000L)
                            if (!manual) throw IllegalStateException("Tap timeout: \"$th\"${lastScanError?.let { " ($it)" } ?: ""}")
                        }

                        chosen = tappedLocator
                        onLog("✓ tapped")
                        handleDialogWithPolling(step.index, onLog, onStatus, 1600L)
                    }

                    StepType.CHECK -> {
                        runCatching {
                            val v = analyzeWithVision("scope.check", onLog)
                            val hasFromTo = v?.elements?.any {
                                it.text.equals("from", true) || it.text.equals(
                                    "to",
                                    true
                                )
                            } == true ||
                                    driver.findElements(AppiumBy.xpath("//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='from' or translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='to']"))
                                        .isNotEmpty()
                            if (!hasFromTo) activeScope = null
                        }.onFailure { /* ignore */ }


                        val th = step.targetHint ?: error("Missing target for CHECK")
                        val preferredSection = step.meta["section"] ?: parseSectionFromHint(th)
                        val effectiveSection = preferredSection ?: activeScope


                        val desireToken = step.value?.trim()?.lowercase()
                        val desired: Boolean? = when (desireToken) {
                            "on", "true", "checked", "tick", "select" -> true
                            "off", "false", "unchecked", "untick", "deselect" -> false
                            else -> null
                        }

                        onStatus("Checking \"$th\"")
                        ensureNotStopped()

                        val pVision = run {
                            val v = analyzeWithVisionFast("check.find", onLog, effectiveSection)

                            if (v == null) null else ScreenVision.findToggleForLabel(
                                driver, v, th, effectiveSection
                            )
                        }

                        val p = pVision ?: findSwitchOrCheckableForLabel(th, preferredSection)
                        if (p != null) {
                            val (loc, el) = p
                            val isChecked = (runCatching { el.getAttribute("checked") }.getOrNull() ?: "false") == "true"
                            val want = desired ?: !isChecked
                            if (isChecked != want) el.click()
                            onLog("✓ checkbox/switch -> ${if (want) "ON" else "OFF"} (xpath=${loc.value})")
                            chosen = loc
                        } else {
                            val all = extractCandidatesForHint(driver, th, limit = 80)
                            val v = analyzeWithVision("check.hint", onLog)
                            val thAug =
                                if (v != null) th + "\n\nVISION_HINT::" + buildVisionHint(v, preferredSection) else th
                            val pickId = selector?.select(thAug, all)?.candidateId
                            val xpUnion =
                                "((//android.widget.CheckBox) | (//android.widget.Switch) | (//*[contains(@resource-id,'check') or contains(@resource-id,'tick') or contains(@content-desc,'check')]))[1]"
                            val loc = all.firstOrNull { it.id == pickId }?.let { Locator(Strategy.XPATH, it.xpath) }
                                ?: Locator(Strategy.XPATH, xpUnion)
                            val el = driver.findElement(loc.toBy())
                            val isChecked = (runCatching { el.getAttribute("checked") }.getOrNull() ?: "false") == "true"
                            val want = desired ?: !isChecked
                            if (isChecked != want) el.click()
                            chosen = loc
                        }
                        handleDialogWithPolling(step.index, onLog, onStatus, DIALOG_WINDOW_AFTER_STEP_MS)
                    }

                    StepType.WAIT_TEXT -> {
                        runCatching {
                            val v = analyzeWithVision("scope.check", onLog)
                            val hasFromTo = v?.elements?.any {
                                it.text.equals("from", true) || it.text.equals(
                                    "to",
                                    true
                                )
                            } == true ||
                                    driver.findElements(AppiumBy.xpath("//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='from' or translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='to']"))
                                        .isNotEmpty()
                            if (!hasFromTo) activeScope = null
                        }.onFailure { /* ignore */ }

                        val q = (step.targetHint ?: step.value) ?: error("Missing query for WAIT_TEXT")
                        val timeout = step.meta["timeoutMs"]?.toLongOrNull() ?: DEFAULT_WAIT_TEXT_TIMEOUT_MS
                        onStatus("Waiting for \"$q\" (timeout ${timeout}ms)")
                        var lastErr: Throwable? = null
                        var recorded: Locator? = null

                        repeat(2) { r ->
                            ensureNotStopped()
                            try {
                                resolver.waitForStableUi()
                                runCatching { handleDialogWithPolling(step.index, onLog, onStatus, 1400L) }
                                val original = resolver.waitForElementPresent(targetHint = q, timeoutMs = timeout, clickIfFound = false) { msg -> onLog("  $msg") }
                                val validated = preMaterializeValidatedXPath(driver, original)
                                recorded = validated?.toLocatorWith(original) ?: original
                                onLog("✓ visible")
                            } catch (e: Throwable) {
                                lastErr = e; onLog("  retry(${r + 1}) WAIT_TEXT: ${e.message}"); Thread.sleep(380)
                            }
                        }
                        if (recorded == null) throw IllegalStateException("WAIT_TEXT timeout: $q${lastErr?.let { " (${it.message})" } ?: ""}")
                        chosen = recorded
                        handleDialogWithPolling(step.index, onLog, onStatus, DIALOG_WINDOW_AFTER_STEP_MS)
                    }

                    StepType.SCROLL_TO -> {
                        runCatching {
                            val v = analyzeWithVision("scope.check", onLog)
                            val hasFromTo = v?.elements?.any {
                                it.text.equals("from", true) || it.text.equals(
                                    "to",
                                    true
                                )
                            } == true ||
                                    driver.findElements(AppiumBy.xpath("//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='from' or translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='to']"))
                                        .isNotEmpty()
                            if (!hasFromTo) activeScope = null
                        }.onFailure { /* ignore */ }

                        val th = step.targetHint ?: error("Missing target for SCROLL_TO")
                        val preferredSection = step.meta["section"] ?: parseSectionFromHint(th)
                        val effectiveSection = preferredSection ?: activeScope


                        onStatus("Scrolling to \"$th\"")
                        ensureNotStopped()
                        resolver.scrollToText(th)
                        if (preferredSection != null) resolver.scrollToText(effectiveSection ?: "")
                        onLog("✓ scrolled")
                        handleDialogWithPolling(step.index, onLog, onStatus, DIALOG_WINDOW_AFTER_STEP_MS)
                    }

                    StepType.WAIT_OTP -> {
                        val digits = step.value?.toIntOrNull() ?: 6
                        onStatus("Waiting for OTP ($digits digits)")
                        ensureNotStopped()
                        resolver.waitForText("length:$digits", timeoutMs = 30_000)
                        onLog("✓ OTP detected")
                        handleDialogWithPolling(step.index, onLog, onStatus, DIALOG_WINDOW_AFTER_STEP_MS)
                    }

                    StepType.ASSERT_TEXT -> {
                        runCatching {
                            val v = analyzeWithVision("scope.check", onLog)
                            val hasFromTo = v?.elements?.any {
                                it.text.equals("from", true) || it.text.equals(
                                    "to",
                                    true
                                )
                            } == true ||
                                    driver.findElements(AppiumBy.xpath("//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='from' or translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='to']"))
                                        .isNotEmpty()
                            if (!hasFromTo) activeScope = null
                        }.onFailure { /* ignore */ }

                        val th = (step.targetHint ?: step.value) ?: error("Missing target for ASSERT_TEXT")
                        onStatus("Asserting \"$th\" is visible")
                        ensureNotStopped()
                        val original = resolver.waitForElementPresent(targetHint = th, timeoutMs = 12_000, clickIfFound = false) { msg -> onLog("  $msg") }
                        val validated = preMaterializeValidatedXPath(driver, original)
                        chosen = validated?.toLocatorWith(original) ?: original
                        onLog("✓ assertion passed")
                        handleDialogWithPolling(step.index, onLog, onStatus, DIALOG_WINDOW_AFTER_STEP_MS)
                    }

                    StepType.BACK -> {
                        onStatus("Navigating back")
                        ensureNotStopped()
                        driver.navigate().back()
                        onLog("✓ back")
                        store.capture(step.index, step.type, step.targetHint, null, true, null)
                        handleDialogWithPolling(step.index, onLog, onStatus, DIALOG_WINDOW_AFTER_STEP_MS)
                    }

                    StepType.SLEEP -> {
                        val ms = (step.value ?: "500").toLong()
                        onStatus("Sleeping ${ms}ms")
                        val slice = 100L; var slept = 0L
                        while (slept < ms) { ensureNotStopped(); Thread.sleep(slice); slept += slice }
                        onLog("✓ wake")
                        store.capture(step.index, step.type, step.targetHint, null, true, null)
                        handleDialogWithPolling(step.index, onLog, onStatus, DIALOG_WINDOW_AFTER_STEP_MS)
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
                        withRetry(attempts = 2, delayMs = 500, onError = { n, e -> onLog("  retry($n) SLIDE: ${e.message}") }) {
                            ensureNotStopped()
                            resolver.waitForStableUi()
                            InputEngine.slideRightByHint(driver, resolver, th) { msg -> onLog("  $msg") }
                        }
                        onLog("✓ slid")
                        handleDialogWithPolling(step.index, onLog, onStatus, DIALOG_WINDOW_AFTER_STEP_MS)
                    }
                }
            } catch (e: InterruptedException) {
                ok = false; notes = "STOP_REQUESTED"; onStatus("⏹️ Stopped"); onLog("⏹️ stop: ${e.message}")
            } catch (e: Exception) {
                ok = false; notes = e.message; onStatus("❌ ${step.type} failed: ${e.message}"); onLog("❌ error: $e")
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

    // ---------- dialogs ----------

    private fun handleDialogWithPolling(
        stepIndex: Int,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        windowMs: Long
    ): Boolean {
        val start = System.currentTimeMillis()
        var handled = false
        while (System.currentTimeMillis() - start < windowMs) {
            val dlg = detectBlockingDialog()
            if (dlg == null) { Thread.sleep(DIALOG_POLL_MS); continue }
            val title = dlg.title ?: ""
            val message = dlg.message ?: ""
            onLog("⚠️ Dialog → title='${title}' message='${message.take(120)}' buttons=${dlg.buttons.map { it.text }}")
            val msgL = (title + " " + message).lowercase()
            val retry = dlg.buttons.firstOrNull { it.text.equals("retry", true) || it.text.equals("try again", true) }
            val ok = dlg.buttons.firstOrNull { it.text.equals("ok", true) || it.text.equals("close", true) || it.text.equals("dismiss", true) }
            val primary = retry ?: ok ?: dlg.buttons.first()
            driver.findElement(AppiumBy.xpath(primary.xpath)).click()
            onLog("  ✓ tapped dialog '${primary.text}'")
            store.capture(stepIndex, StepType.TAP, "DIALOG: ${primary.text}", Locator(Strategy.XPATH, primary.xpath), true, "AUTO_DIALOG")
            val isAuthBlock = listOf("invalid credential", "not able to log you in", "not eligible").any { it in msgL }
            if (isAuthBlock) {
                onStatus("❌ Login failed: ${message.ifBlank { title }}")
                throw IllegalStateException("BLOCKING_DIALOG: INVALID_CREDENTIALS")
            }
            handled = true
            Thread.sleep(220)
            break
        }
        return handled
    }

    private data class DialogBtn(val text: String, val xpath: String)
    private data class DetectedDialog(val title: String?, val message: String?, val buttons: List<DialogBtn>, val rootXPath: String?)

    private fun detectBlockingDialog(): DetectedDialog? {
        val btnNodes = driver.findElements(
            AppiumBy.xpath(
                "//*[self::android.widget.Button or (self::android.widget.TextView and @clickable='true') or (self::android.widget.CheckedTextView and @clickable='true')]"
            )
        )
        if (btnNodes.isEmpty()) return null
        val labelSet = setOf("ok","okay","retry","try again","cancel","close","dismiss","continue","yes","no","allow","deny","got it","understood","confirm")
        val candidate = btnNodes.firstOrNull {
            val t = (runCatching { it.text }.getOrNull() ?: "").trim().lowercase()
            t.isNotEmpty() && t in labelSet
        } ?: return null
        val container = candidate.findElements(By.xpath("ancestor::*[@resource-id][1]")).firstOrNull()
            ?: candidate.findElements(By.xpath("ancestor::*[1]")).firstOrNull() ?: return null
        val rid = runCatching { container.getAttribute("resource-id") }.getOrNull()?.trim().orEmpty()
        val rootXp = if (rid.isNotEmpty()) "//*[@resource-id=${xpathLiteral(rid)}]" else null
        val titleEl = container.findElements(AppiumBy.xpath(".//*[@resource-id='android:id/alertTitle' or contains(translate(@resource-id,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'title')][1]")).firstOrNull()
        val msgElKnown = container.findElements(AppiumBy.xpath(".//*[@resource-id='android:id/message' or contains(translate(@resource-id,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'message')][1]")).firstOrNull()
        val nonClickableTexts = container.findElements(AppiumBy.xpath(".//android.widget.TextView[normalize-space(@text)!='' and @clickable='false']"))
        val messageEl = msgElKnown ?: nonClickableTexts.maxByOrNull { (runCatching { it.text }.getOrNull() ?: "").length }
        val titleElFinal = titleEl ?: nonClickableTexts.minByOrNull { (runCatching { it.text }.getOrNull() ?: "").length }
        val title = runCatching { titleElFinal?.text }.getOrNull()?.trim()
        val message = runCatching { messageEl?.text }.getOrNull()?.trim()
        val buttonNodes = container.findElements(AppiumBy.xpath(".//*[self::android.widget.Button or (self::android.widget.TextView and @clickable='true') or (self::android.widget.CheckedTextView and @clickable='true')]"))
        val buttons = buttonNodes.mapNotNull { b ->
            val t = (runCatching { b.text }.getOrNull() ?: "").trim()
            if (t.isEmpty()) null else {
                val xp = rootXp?.let { "$it//*[normalize-space(@text)=${xpathLiteral(t)}]" } ?: "//*[normalize-space(@text)=${xpathLiteral(t)}]"
                DialogBtn(t, xp)
            }
        }.distinctBy { it.text.lowercase() }
        if (buttons.isEmpty()) return null
        return DetectedDialog(title = title, message = message, buttons = buttons, rootXPath = rootXp)
    }

    // ---------- xpath helpers ----------

    private data class ValidatedXPath(val xpath: String, val element: WebElement)

    private fun preMaterializeValidatedXPath(driver: AndroidDriver, original: Locator): ValidatedXPath? {
        val live = driver.findElement(original.toBy())
        val candidates = buildXPathCandidates(live)
        for (xp in candidates) {
            val matches = driver.findElements(AppiumBy.xpath(xp))
            if (matches.size == 1 && isSameElement(matches[0], live)) return ValidatedXPath(xp, matches[0])
        }
        for (xp in candidates) {
            val matches = driver.findElements(AppiumBy.xpath(xp))
            val same = matches.firstOrNull { isSameElement(it, live) }
            if (same != null) return ValidatedXPath(xp, same)
        }
        return null
    }

    private fun buildXPathCandidates(el: WebElement): List<String> {
        val resId = el.attrSafe("resource-id")
        val contentDesc = el.attrSafe("content-desc").ifEmpty { el.attrSafe("contentDescription") }
        val txtRaw = (runCatching { el.text }.getOrNull() ?: el.attrSafe("text")).trim()
        fun eqText(t: String) = "//*[@text=${xpathLiteral(t)}]"
        fun eqTextCI(t: String) =
            "//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')=" +
                    "translate(${xpathLiteral(t)},'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')]"
        val out = mutableListOf<String>()
        if (resId.isNotEmpty()) out += "//*[@resource-id=${xpathLiteral(resId)}]"
        if (contentDesc.isNotEmpty()) out += "//*[@content-desc=${xpathLiteral(contentDesc)}]"
        if (txtRaw.isNotEmpty()) { out += eqText(txtRaw); out += eqTextCI(txtRaw) }
        if (out.isEmpty()) {
            val texts = el.findElements(By.xpath(".//android.widget.TextView[normalize-space(@text)!='' and @clickable='false']"))
                .mapNotNull { runCatching { it.text }.getOrNull()?.trim() }.filter { it.isNotEmpty() }.distinct()
            val titleish = texts.filter { it.length in 3..40 && !it.endsWith(".") && it.count { ch -> ch.isWhitespace() } <= 4 }.sortedBy { it.length }
            val picks =
                buildList { addAll(titleish.take(3)); if (titleish.isEmpty() && texts.isNotEmpty()) add(texts.minBy { it.length }) }.distinct()
            for (t in picks) {
                out += "(//*[normalize-space(@text)=${xpathLiteral(t)}]/ancestor::*[@clickable='true'][1])"
                out += "(" +
                        "//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')=" +
                        "translate(${xpathLiteral(t)},'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')]" +
                        "/ancestor::*[@clickable='true'][1])"
            }
            out += "//*[self::android.widget.LinearLayout or self::android.view.ViewGroup][@clickable='true']"
        }
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


    private fun ValidatedXPath.toLocatorWith(original: Locator): Locator =
        Locator(strategy = Strategy.XPATH, value = this.xpath, alternatives = listOf(original.strategy to original.value))

    // ---------- helpers ----------

    private fun Locator.toBy(): By = when (strategy) {
        Strategy.XPATH -> By.xpath(value)
        Strategy.ID -> AppiumBy.id(value)
        Strategy.DESC -> AppiumBy.accessibilityId(value)
        Strategy.UIAUTOMATOR -> AppiumBy.androidUIAutomator(value)
        else -> By.xpath(value)
    }

    private fun strictEquals(hint: String, label: String?): Boolean =
        label?.trim()?.equals(hint.trim(), ignoreCase = true) == true

    private fun nearEquals(hint: String, label: String?): Boolean {
        if (label == null) return false
        fun norm(s: String): String = s.lowercase().replace("&", " and ").replace(Regex("[\\p{Punct}]"), " ").replace(Regex("\\s+"), " ").trim()
        return norm(label) == norm(hint)
    }

    private fun preferByRoleAndPosition(list: List<UICandidate>): List<UICandidate> {
        return list.sortedWith(compareByDescending<UICandidate> {
            val role = (it.role ?: "").lowercase()
            when {
                "button" in role -> 3
                "tab" in role || "bottom" in role || "nav" in role -> 2
                else -> 0
            }
        }.thenByDescending {
            val rect = runCatching { driver.findElement(AppiumBy.xpath(it.xpath)).rect }.getOrNull()
            rect?.y ?: 0
        })
    }

    private fun safePageSourceHash(): Int = runCatching { driver.pageSource.hashCode() }.getOrDefault(0)

    private fun waitUiChangedSince(prevHash: Int, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val cur = safePageSourceHash()
            if (cur != prevHash) return true
            Thread.sleep(120)
        }
        return false
    }

    private inline fun <T> withRetry(attempts: Int, delayMs: Long, onError: (Int, Throwable) -> Unit = { _, _ -> }, block: () -> T): T {
        var last: Throwable? = null
        repeat(attempts) { i -> try { return block() } catch (e: Throwable) { last = e; onError(i + 1, e); Thread.sleep(delayMs) } }
        throw last ?: IllegalStateException("failed")
    }

    private fun isBusy(): Boolean {
        val xp = "//*[@indeterminate='true' and self::android.widget.ProgressBar] | //*[contains(translate(@content-desc,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'progress') and @clickable='false']"
        return runCatching { driver.findElements(AppiumBy.xpath(xp)).isNotEmpty() }.getOrDefault(false)
    }

    private fun waitWhileBusy(maxMs: Long): Boolean {
        val start = System.currentTimeMillis()
        var sawBusy = false
        while (System.currentTimeMillis() - start < maxMs) {
            if (isBusy()) { sawBusy = true; Thread.sleep(200); continue }
            if (sawBusy) Thread.sleep(150)
            return sawBusy
        }
        return sawBusy
    }

    private fun parseSectionFromHint(hint: String): String? {
        val s = hint.lowercase()
        if (Regex("\\bfor\\s+from\\b|\\bin\\s+from\\b|\\bfrom\\s*:\\s*").containsMatchIn(s)) return "from"
        if (Regex("\\bfor\\s+to\\b|\\bin\\s+to\\b|\\bto\\s*:\\s*").containsMatchIn(s)) return "to"
        return null
    }

    private fun applySectionScope(all: List<UICandidate>, preferred: String?): List<UICandidate> {
        if (preferred == null) return all
        val headers = driver.findElements(AppiumBy.xpath("//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='from' or translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='to']"))
        if (headers.isEmpty()) return all
        var fromY: Int? = null
        var toY: Int? = null
        headers.forEach {
            val t = (runCatching { it.text }.getOrNull() ?: "").trim().lowercase()
            if (t == "from") fromY = it.rect.y
            if (t == "to") toY = it.rect.y
        }
        fun inFrom(y: Int): Boolean {
            if (fromY == null && toY == null) return true
            if (fromY != null && toY != null) return y in (kotlin.math.min(
                fromY!!,
                toY!!
            ) - 40)..(kotlin.math.max(fromY!!, toY!!) - 40)
            if (fromY != null) return y >= fromY!! - 40
            return y >= toY!! - 40
        }
        fun inTo(y: Int): Boolean = if (toY != null) y >= toY!! - 40 else true

        return all.filter {
            val rect = runCatching { driver.findElement(AppiumBy.xpath(it.xpath)).rect }.getOrNull()
            if (rect == null) true else {
                when (preferred.lowercase()) {
                    "from" -> inFrom(rect.y)
                    "to" -> inTo(rect.y)
                    else -> true
                }
            }
        }
    }

    private fun findSwitchOrCheckableForLabel(label: String, section: String?): Pair<Locator, WebElement>? {
        // Vision hint (best effort)
        val vres = analyzeWithVision("toggle.find", onLog = { })
        if (vres != null) {
            val vr = ScreenVision.findToggleForLabel(driver, vres, label, section)
            if (vr != null) return vr
        }

        // Pure DOM by label
        val lit = xpathLiteral(label.trim())
        val base = "//*[normalize-space(@text)=$lit]"
        val scoped = if (section == null) base else {
            val sec = section.lowercase()
            val secAnchor = when (sec) {
                "from" -> "(//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='from'])[1]"
                "to" -> "(//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='to'])[1]"
                else -> ""
            }
            if (secAnchor.isNotEmpty()) "($secAnchor/following::${base.removePrefix("//*")})[1]" else base
        }

        val labelEl = driver.findElements(AppiumBy.xpath(scoped)).firstOrNull() ?: return null
        val container = labelEl.findElements(By.xpath("ancestor::*[self::android.view.ViewGroup or self::android.widget.LinearLayout][1]")).firstOrNull() ?: labelEl
        val switchOrCheck = container.findElements(AppiumBy.xpath(".//android.widget.Switch | .//*[@checkable='true']")).firstOrNull()
            ?: run {
                val rowRight = container.findElements(AppiumBy.xpath(".//*[self::android.widget.Switch or @checkable='true']")).maxByOrNull { it.rect.x + it.rect.width }
                rowRight
            }
        if (switchOrCheck != null) {
            val xp = buildLocatorForElement(switchOrCheck)
            return Locator(Strategy.XPATH, xp) to switchOrCheck
        }
        return null
    }

    private fun buildLocatorForElement(el: WebElement): String {
        val rid = runCatching { el.getAttribute("resource-id") }.getOrNull()?.takeIf { it.isNotBlank() }
        if (rid != null) return "//*[@resource-id=${xpathLiteral(rid)}]"
        val txt = runCatching { el.text }.getOrNull()?.trim().orEmpty()
        if (txt.isNotEmpty()) return "//*[normalize-space(@text)=${xpathLiteral(txt)}]"
        return "//*[@checkable='true'][1]"
    }

    private fun parseDesiredToggle(hint: String): Boolean? {
        val s = hint.lowercase()
        return when {
            Regex("\\bturn\\s+on\\b|\\bon\\b|\\benable\\b|\\btoggle\\s+on\\b").containsMatchIn(s) -> true
            Regex("\\bturn\\s+off\\b|\\boff\\b|\\bdisable\\b|\\btoggle\\s+off\\b").containsMatchIn(s) -> false
            else -> null
        }
    }

    private fun tryToggleByLabel(label: String, section: String?, desired: Boolean?, onLog: (String) -> Unit): Pair<Boolean, Locator?> {
        val pair = findSwitchOrCheckableForLabel(label, section) ?: return false to null
        val isChecked = (runCatching { pair.second.getAttribute("checked") }.getOrNull() ?: "false") == "true"
        if (desired == null) runCatching { pair.second.click() } else if (isChecked != desired) runCatching { pair.second.click() }
        return true to pair.first
    }

    private fun screenshotPng(): ByteArray = driver.getScreenshotAs(OutputType.BYTES)

    private fun buildVisionHint(v: VisionResult, section: String?): String {
        val texts = v.elements.mapNotNull { e ->
            val t = e.text?.trim()
            if (t.isNullOrEmpty()) null else Triple(t, e.y, e.x)
        }.sortedWith(compareBy({ it.second }, { it.third })).take(40)
        val fromY = v.elements.firstOrNull { (it.text ?: "").equals("from", true) }?.y
        val toY = v.elements.firstOrNull { (it.text ?: "").equals("to", true) }?.y
        val head = "image=${v.imageW}x${v.imageH}; section=${section ?: "-"}; fromY=${fromY ?: -1}; toY=${toY ?: -1}"
        val body = texts.joinToString("; ") { "'${it.first.replace(";", " ")}'@y=${it.second}" }
        return "$head; $body"
    }

    private fun analyzeWithVision(tag: String, onLog: (String) -> Unit): VisionResult? {
        val client = deki ?: run { onLog("vision:$tag disabled"); return null }
        return try {
            onLog("vision:$tag analyze:start")
            val res = client.analyzePngWithLogs(screenshotPng(), log = { line ->
                onLog("vision:$tag $line")
            })
            if (res == null) onLog("vision:$tag analyze:null")
            res
        } catch (t: Throwable) {
            onLog("vision:$tag error=${t.message}")
            null
        }
    }


    private fun logVisionSummary(tag: String, v: VisionResult, section: String?, onLog: (String) -> Unit) {
        val topTexts = v.elements
            .mapNotNull { e -> e.text?.trim()?.takeIf { it.isNotEmpty() }?.let { t -> t to e.y } }
            .sortedBy { it.second }
            .map { it.first }
            .distinct()
            .take(10)
        onLog("vision:$tag summary section=${section ?: "-"} texts=${topTexts.joinToString(" | ")}")
    }

    private fun determineScopeByY(y: Int?, v: VisionResult?): String? {
        if (y == null) return null

        fun fromYVision(): Int? = v?.elements?.firstOrNull { it.text?.equals("from", true) == true }?.y
        fun toYVision(): Int? = v?.elements?.firstOrNull { it.text?.equals("to", true) == true }?.y

        val fromY = fromYVision() ?: runCatching {
            driver.findElements(
                AppiumBy.xpath("//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='from']")
            ).firstOrNull()?.rect?.y
        }.getOrNull()

        val toY = toYVision() ?: runCatching {
            driver.findElements(
                AppiumBy.xpath("//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='to']")
            ).firstOrNull()?.rect?.y
        }.getOrNull()

        return when {
            fromY != null && toY != null -> if (y < toY) "from" else "to"
            fromY != null -> if (y >= fromY) "from" else null
            toY != null -> if (y >= toY) "to" else null
            else -> null
        }
    }

    private fun preferByRolePosAndAnchor(list: List<UICandidate>, anchorY: Int?): List<UICandidate> {
        return list.sortedWith(
            compareByDescending<UICandidate> {
                val role = (it.role ?: "").lowercase()
                when {
                    "button" in role -> 3
                    "tab" in role || "bottom" in role || "nav" in role -> 2
                    else -> 0
                }
            }.thenBy {
                val y = runCatching { driver.findElement(AppiumBy.xpath(it.xpath)).rect.y }.getOrNull() ?: Int.MAX_VALUE
                if (anchorY == null) Int.MAX_VALUE else kotlin.math.abs(y - anchorY)
            }
        )
    }

    private fun cropForScope(png: ByteArray, scope: String?): ByteArray {
        if (scope == null) return png
        return runCatching {
            val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(png))
            val h = img.height
            val w = img.width
            val (y0, y1) = when (scope.lowercase()) {
                "from" -> 0 to (h * 0.55).toInt()     // top ~55%
                "to" -> (h * 0.45).toInt() to h     // bottom ~55%
                else -> 0 to h
            }
            val sub = img.getSubimage(0, y0.coerceAtLeast(0), w, (y1 - y0).coerceAtLeast(1))
            val baos = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(sub, "png", baos)
            baos.toByteArray()
        }.getOrElse { png }
    }

    // FAST: small width, small net, no OCR
    private fun analyzeWithVisionFast(
        tag: String,
        onLog: (String) -> Unit,
        effectiveSection: String?
    ): VisionResult? {
        val client = deki ?: run { onLog("vision:$tag disabled"); return null }

        // cache by pageSource + scope
        val hash = safePageSourceHash()
        if (lastVision != null && lastVisionHash == hash && lastVisionScope == effectiveSection) {
            onLog("vision:$tag cache:hit")
            return lastVision
        }

        onLog("vision:$tag analyze:start")
        val shot = screenshotPng()
        val cropped = cropForScope(shot, effectiveSection)

        val res = try {
            // force fast settings here regardless of env
            client.analyzePngWithLogs(
                png = cropped,
                log = { line -> onLog("vision:$tag $line") },
                maxW = 480,        // <— width downscale before sending to Python
                imgsz = 320,       // <— YOLO input size
                conf = 0.25f,
                ocr = false       // <— disable OCR in fast path
            )
        } catch (t: Throwable) {
            onLog("vision:$tag error=${t.message}")
            null
        }

        if (res == null) onLog("vision:$tag analyze:null") else {
            onLog("vision:$tag image=${res.imageW}x${res.imageH} items=${res.elements.size}")
        }
        lastVision = res
        lastVisionHash = hash
        lastVisionScope = effectiveSection
        return res
    }

    private fun analyzeWithVisionSlowForText(
        tag: String,
        onLog: (String) -> Unit,
        effectiveSection: String?
    ): VisionResult? {
        val client = deki ?: return null
        val shot = screenshotPng()
        val cropped = cropForScope(shot, effectiveSection)
        return client.analyzePngWithLogs(
            png = cropped,
            log = { line -> onLog("vision:$tag $line") },
            maxW = 640, imgsz = 416, conf = 0.25f, ocr = true
        )
    }
}
