package agent

import io.appium.java_client.android.AndroidDriver

class AgentRunner(
    private val driver: AndroidDriver,
    private val resolver: LocatorResolver,
    private val store: SnapshotStore
) {
    /**
     * onStep   -> called after every step (for timeline/progress)
     * onLog    -> granular logs (locator attempts, errors)
     * onStatus -> high-level status for the UI ("Launching app…", "Tapping LOGIN"...)
     */
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
                        onStatus("Launching ${step.targetHint}")
                        driver.activateApp(step.targetHint ?: error("Missing package"))
                        onLog("✓ app activated")
                    }


                    StepType.INPUT_TEXT -> {
                        val th = step.targetHint ?: error("Missing target for INPUT_TEXT")
                        onStatus("Typing into \"$th\"")
                        InputEngine.type(
                            driver = driver,
                            resolver = resolver,
                            targetHint = th,
                            value = step.value ?: "",
                            log = { msg -> onLog("  $msg") }
                        )
                    }



                    StepType.TAP -> {
                        val th = step.targetHint ?: error("Missing target for TAP")
                        onStatus("Tapping \"$th\"")
                        chosen = resolver.resolve(th) { msg -> onLog("  $msg") }
                        driver.findElement(chosen.toBy()).click()
                        onLog("✓ tapped")
                    }
                    StepType.SCROLL_TO -> {
                        onStatus("Scrolling to \"${step.targetHint}\"")
                        resolver.scrollToText(step.targetHint!!)
                        onLog("✓ scrolled")
                    }
                    StepType.WAIT_TEXT -> {
                        onStatus("Waiting for \"${step.targetHint}\"")
                        resolver.waitForText(step.targetHint!!)
                        onLog("✓ visible")
                    }
                    StepType.ASSERT_TEXT -> {
                        onStatus("Asserting \"${step.targetHint}\" is present")
                        resolver.assertText(step.targetHint!!)
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
                onLog("❌ error: ${e}")
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
