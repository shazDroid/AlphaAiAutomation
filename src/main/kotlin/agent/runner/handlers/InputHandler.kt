package agent.runner.handlers

import adb.UiDumpParser.findEditTextForLabel
import agent.Locator
import agent.PlanStep
import agent.StepType
import agent.Strategy
import agent.runner.RunContext
import agent.runner.StepOutcome
import agent.runner.services.DialogService
import agent.runner.services.MemoryService
import agent.runner.services.UiService
import agent.runner.services.XPathService

/**
 * Handles INPUT_TEXT with memory-first and reliable focus tracking.
 */
class InputHandler(
    private val ctx: RunContext,
    private val ui: UiService,
    private val xpaths: XPathService,
    private val memory: MemoryService,
    private val dialog: DialogService
) {
    fun input(step: PlanStep): StepOutcome {
        val th = step.targetHint ?: return StepOutcome(false, notes = "Missing target for INPUT_TEXT")
        val value = step.value ?: return StepOutcome(false, notes = "Missing value for INPUT_TEXT")

        val saved = memory.find(StepType.INPUT_TEXT, th)
        for (loc in saved) {
            if (tryInputByLocator(loc, value)) {
                dialog.afterStep(step, 2400)
                memory.save(StepType.INPUT_TEXT, th, loc, null)
                return StepOutcome(true, chosen = loc)
            }
        }

        val chosen = withRetry(3, 650) {
            ctx.resolver.waitForStableUi()
            val (xp, edit) = findEditTextForLabel(ctx.driver, th.trim(), th) { }
            runCatching { edit.click() }
            ctx.lastTapY = runCatching { edit.rect.let { it.y + it.height / 2 } }.getOrNull()
            edit.clear()
            edit.sendKeys(value)
            Locator(Strategy.XPATH, xp)
        }
        dialog.afterStep(step, 2400)
        agent.ui.Gestures.hideKeyboardIfOpen(ctx.driver) { }
        memory.save(StepType.INPUT_TEXT, th, chosen, saved.firstOrNull())
        return StepOutcome(true, chosen = chosen)
    }

    private fun tryInputByLocator(loc: Locator, value: String): Boolean {
        return try {
            val by = xpaths.toBy(loc)
            var el = runCatching { ctx.driver.findElement(by) }.getOrNull() ?: return false
            var i = 0
            while (!ui.isFullyVisible(el) && i++ < 6) {
                agent.ui.Gestures.standardScrollUp(ctx.driver)
                ctx.resolver.waitForStableUi()
                el = runCatching { ctx.driver.findElement(by) }.getOrNull() ?: return false
            }
            runCatching { el.click() }
            ctx.lastTapY = runCatching { el.rect.let { it.y + it.height / 2 } }.getOrNull()
            el.clear()
            el.sendKeys(value)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private inline fun <T> withRetry(attempts: Int, delayMs: Long, crossinline block: () -> T): T {
        var last: Throwable? = null
        repeat(attempts) {
            try {
                return block()
            } catch (e: Throwable) {
                last = e; Thread.sleep(delayMs)
            }
        }
        throw last ?: IllegalStateException("failed")
    }
}
