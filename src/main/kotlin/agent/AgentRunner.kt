package agent

import io.appium.java_client.android.AndroidDriver

class AgentRunner(
    private val driver: AndroidDriver,
    private val resolver: LocatorResolver,
    private val store: SnapshotStore
) {
    private val DEFAULT_WAIT_TEXT_TIMEOUT_MS = 45_000L
    private val DEFAULT_TAP_TIMEOUT_MS = 15_000L

    fun run(
        plan: ActionPlan,
        onStep: (Snapshot) -> Unit = {},
        onLog: (String) -> Unit = {},
        onStatus: (String) -> Unit = {}
    ): List<Snapshot> {
        val out = mutableListOf<Snapshot>()
        onLog("Plan started: \"${plan.title}\" (${plan.steps.size} steps)")

        for (step in plan.steps) {
            onStatus("Step ${step.index}/${plan.steps.size}: ${step.type} ${step.targetHint ?: ""}")
            onLog("➡️  ${step.index} ${step.type} target='${step.targetHint}' value='${step.value}'")

            var ok = true
            var chosen: Locator? = null
            var notes: String? = null

            try {
                when (step.type) {
                    StepType.LAUNCH_APP -> {
                        val pkg = step.targetHint ?: error("Missing package for LAUNCH_APP")

                        // If the session already has the target app in foreground, just skip activation.
                        val currentPkg = runCatching { driver.currentPackage }.getOrNull()
                        val alreadyOk = !currentPkg.isNullOrBlank() && currentPkg == pkg
                        if (alreadyOk) {
                            onLog("✓ app already running in foreground: $currentPkg (skipping activateApp)")
                        } else {
                            onStatus("Launching $pkg")
                            val activated = runCatching { driver.activateApp(pkg) }
                                .onSuccess { onLog("✓ app activated: $pkg") }
                                .isSuccess

                            if (!activated) {
                                onLog("! activateApp failed, attempting monkey fallback")
                                runCatching {
                                    (driver as org.openqa.selenium.JavascriptExecutor).executeScript(
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
                    }



                    StepType.INPUT_TEXT -> {
                        val th = step.targetHint ?: error("Missing target for INPUT_TEXT")
                        onStatus("Typing into \"$th\"")
                        chosen = withRetry(attempts = 3, delayMs = 700,
                            onError = { n, e -> onLog("  retry($n) INPUT_TEXT: ${e.message}") }) {
                            resolver.waitForStableUi()
                            InputEngine.type(driver, resolver, th, step.value ?: "", log = { msg -> onLog("  $msg") })
                        }
                        onLog("✓ input done")
                    }


                    StepType.TAP -> {
                        val th = step.targetHint ?: error("Missing target for TAP")
                        val pkgLike = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+\$")
                        if (pkgLike.matches(th)) {
                            onLog("↪︎ skip TAP on package name '$th' (invalid target)")
                            onStatus("Skipping invalid tap on package")
                        } else {
                            onStatus("Tapping \"$th\"")
                            chosen = resolver.waitForElementPresent(th, timeoutMs = 15000, clickIfFound = true) { msg -> onLog("  $msg") }
                            onLog("✓ tapped")
                        }
                    }


                    StepType.WAIT_TEXT -> {
                        val q = (step.targetHint ?: step.value)
                            ?: error("Missing query for WAIT_TEXT")
                        val timeout = step.meta["timeoutMs"]?.toLongOrNull()
                            ?: DEFAULT_WAIT_TEXT_TIMEOUT_MS
                        onStatus("Waiting for \"$q\" (timeout ${timeout}ms)")
                        var okWait = false
                        var lastErr: Throwable? = null
                        repeat(2) { r ->
                            try {
                                resolver.waitForStableUi()
                                chosen = resolver.waitForElementPresent(
                                    targetHint = q,
                                    timeoutMs = timeout,
                                    clickIfFound = false
                                ) { msg -> onLog("  $msg") }
                                okWait = true
                                return@repeat
                            } catch (e: Throwable) {
                                lastErr = e
                                onLog("  retry(${r + 1}) WAIT_TEXT: ${e.message}")
                                Thread.sleep(400)
                            }
                        }
                        if (!okWait) throw IllegalStateException("WAIT_TEXT timeout: $q${lastErr?.let { " (${it.message})" } ?: ""}")
                        onLog("✓ visible")
                    }

                    StepType.SCROLL_TO -> {
                        val th = step.targetHint ?: error("Missing target for SCROLL_TO")
                        onStatus("Scrolling to \"$th\"")
                        resolver.scrollToText(th)
                        onLog("✓ scrolled")

                        runCatching {
                            val loc = resolver.waitForElementPresent(th, timeoutMs = 4000, clickIfFound = true) { msg -> onLog("  $msg") }
                            onLog("✓ (optional) tapped \"$th\" after scroll via ${loc.strategy}")
                        }.onFailure { onLog("  (optional) tap after scroll skipped: ${it.message}") }
                    }

                    StepType.SLIDE -> {
                        val th = step.targetHint ?: error("Missing target for SLIDE")
                        onStatus("Sliding \"$th\"")
                        withRetry(attempts = 2, delayMs = 500, onError = { n, e -> onLog("  retry($n) SLIDE: ${e.message}") }) {
                            resolver.waitForStableUi()
                            InputEngine.slideRightByHint(driver, resolver, th) { msg -> onLog("  $msg") }
                        }
                        onLog("✓ slid")
                    }


                    StepType.WAIT_OTP -> {
                        val digits = step.value?.toIntOrNull() ?: 6
                        val q = "length:$digits"
                        onStatus("Waiting for OTP ($digits digits)")
                        resolver.waitForText(q, timeoutMs = 30_000)
                        onLog("✓ OTP detected")
                    }

                    StepType.ASSERT_TEXT -> {
                        val th = (step.targetHint ?: step.value)
                            ?: error("Missing target for ASSERT_TEXT")
                        onStatus("Asserting \"$th\" is visible")
                        resolver.waitForElementPresent(
                            targetHint = th,
                            timeoutMs = 12_000,
                            clickIfFound = false
                        ) { msg -> onLog("  $msg") }
                        onLog("✓ assertion passed")
                    }

                    StepType.BACK -> {
                        onStatus("Navigating back")
                        driver.navigate().back()
                        onLog("✓ back")
                    }

                    StepType.SLEEP -> {
                        val ms = (step.value ?: "500").toLong()
                        onStatus("Sleeping ${ms}ms")
                        Thread.sleep(ms)
                        onLog("✓ wake")
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
        }

        onLog("Plan completed. Success ${out.count { it.success }}/${out.size}")
        return out
    }
}
