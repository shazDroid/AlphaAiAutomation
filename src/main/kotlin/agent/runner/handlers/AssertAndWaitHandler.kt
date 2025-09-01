package agent.runner.handlers

import agent.PlanStep
import agent.StepType
import agent.runner.RunContext
import agent.runner.StepOutcome
import agent.runner.services.DialogService
import agent.runner.services.UiService
import agent.runner.services.XPathService
import agent.runner.util.ScrollUtil

/**
 * Handles WAIT_TEXT, WAIT_OTP, ASSERT_TEXT.
 */
class AssertAndWaitHandler(
    private val ctx: RunContext,
    private val ui: UiService,
    private val xpaths: XPathService,
    private val dialog: DialogService
) {
    fun handle(step: PlanStep): StepOutcome {
        return when (step.type) {
            StepType.WAIT_TEXT -> waitText(step)
            StepType.ASSERT_TEXT -> assertText(step)
            StepType.WAIT_OTP -> waitOtp(step)
            else -> StepOutcome(true)
        }
    }

    private fun waitText(step: PlanStep): StepOutcome {
        val q = (step.targetHint ?: step.value) ?: return StepOutcome(false, notes = "Missing query for WAIT_TEXT")
        val timeout = step.meta["timeoutMs"]?.toLongOrNull() ?: 45_000L
        val dir = when (step.meta["scrollDir"]?.lowercase()) {
            "up" -> ScrollUtil.Direction.UP
            "down" -> ScrollUtil.Direction.DOWN
            else -> ScrollUtil.Direction.DOWN
        }
        var recorded: agent.Locator? = null
        var lastErr: Throwable? = null
        repeat(2) {
            try {
                ctx.resolver.waitForStableUi()
                dialog.afterStepSilent(1400)
                if (!ctx.driver.pageSource.contains(q, ignoreCase = true)) {
                    ScrollUtil.scrollTextIntoViewMonotonic(ctx.driver, q, direction = dir, maxSwipes = 8)
                }
                val original =
                    ctx.resolver.waitForElementPresent(targetHint = q, timeoutMs = timeout, clickIfFound = false) { }
                val validated = xpaths.validate(original)
                recorded = validated?.let { xpaths.toLocatorWith(it, original) } ?: original
                return@repeat
            } catch (e: Throwable) {
                lastErr = e
                Thread.sleep(380)
            }
        }
        if (recorded == null) return StepOutcome(
            false,
            notes = "WAIT_TEXT timeout: $q${lastErr?.let { " (${it.message})" } ?: ""}")
        dialog.afterStep(step, 2400)
        return StepOutcome(true, chosen = recorded)
    }


    private fun assertText(step: PlanStep): StepOutcome {
        val th = (step.targetHint ?: step.value) ?: return StepOutcome(false, notes = "Missing target for ASSERT_TEXT")
        val dir = when (step.meta["scrollDir"]?.lowercase()) {
            "up" -> ScrollUtil.Direction.UP
            "down" -> ScrollUtil.Direction.DOWN
            else -> ScrollUtil.Direction.DOWN
        }
        if (!ctx.driver.pageSource.contains(th, ignoreCase = true)) {
            ScrollUtil.scrollTextIntoViewMonotonic(ctx.driver, th, direction = dir, maxSwipes = 8)
        }
        val original = ctx.resolver.waitForElementPresent(targetHint = th, timeoutMs = 12_000, clickIfFound = false) { }
        val validated = xpaths.validate(original)
        val chosen = validated?.let { xpaths.toLocatorWith(it, original) } ?: original
        dialog.afterStep(step, 2400)
        return StepOutcome(true, chosen = chosen)
    }


    private fun waitOtp(step: PlanStep): StepOutcome {
        val digits = step.value?.toIntOrNull() ?: 6
        ctx.resolver.waitForText("length:$digits", timeoutMs = 30_000)
        dialog.afterStep(step, 2400)
        return StepOutcome(true)
    }
}
