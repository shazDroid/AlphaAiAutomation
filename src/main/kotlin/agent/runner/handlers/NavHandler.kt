package agent.runner.handlers

import agent.PlanStep
import agent.StepType
import agent.runner.RunContext
import agent.runner.StepOutcome
import agent.runner.services.DialogService
import agent.runner.services.UiService
import agent.runner.services.VisionService
import org.openqa.selenium.JavascriptExecutor

/**
 * Handles BACK, SLEEP, LABEL, GOTO, IF_VISIBLE, LAUNCH_APP, SLIDE.
 */
class NavHandler(
    private val ctx: RunContext,
    private val ui: UiService,
    private val vision: VisionService,
    private val dialog: DialogService
) {
    fun handle(step: PlanStep, pc: Int): StepOutcome {
        return when (step.type) {
            StepType.BACK -> back(step)
            StepType.SLEEP -> sleep(step)
            StepType.LABEL -> StepOutcome(true)
            StepType.GOTO -> goto(step)
            StepType.IF_VISIBLE -> ifVisible(step)
            StepType.SLIDE -> slide(step)
            else -> StepOutcome(true)
        }
    }

    fun launch(step: PlanStep): StepOutcome {
        val pkg = step.targetHint ?: return StepOutcome(false, notes = "Missing package for LAUNCH_APP")
        val currentPkg = runCatching { ctx.driver.currentPackage }.getOrNull()
        if (currentPkg.isNullOrBlank() || currentPkg != pkg) {
            val activated = runCatching { ctx.driver.activateApp(pkg) }.isSuccess
            if (!activated) {
                runCatching {
                    (ctx.driver as JavascriptExecutor).executeScript(
                        "mobile: shell",
                        mapOf(
                            "command" to "monkey",
                            "args" to listOf("-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")
                        )
                    )
                }.getOrElse { e -> return StepOutcome(false, notes = "LAUNCH_APP failed for '$pkg': ${e.message}") }
            }
        }
        dialog.afterStep(step, 2400)
        return StepOutcome(true)
    }

    private fun back(step: PlanStep): StepOutcome {
        ctx.driver.navigate().back()
        dialog.afterStep(step, 2400)
        return StepOutcome(true)
    }

    private fun sleep(step: PlanStep): StepOutcome {
        val ms = (step.value ?: "500").toLong()
        val slice = 100L
        var slept = 0L
        while (slept < ms) {
            if (ctx.stopSignal()) return StepOutcome(false, notes = "STOP_REQUESTED")
            Thread.sleep(slice)
            slept += slice
        }
        dialog.afterStep(step, 2400)
        return StepOutcome(true)
    }

    private fun goto(step: PlanStep): StepOutcome {
        val lbl = step.meta["label"] ?: step.targetHint ?: return StepOutcome(false, notes = "Missing label for GOTO")
        val idx = ctx.plan.steps.indexOfFirst { it.type == StepType.LABEL && it.targetHint == lbl }
        if (idx < 0) return StepOutcome(false, notes = "GOTO target label not found: $lbl")
        dialog.afterStepSilent(0)
        return StepOutcome(true, nextPc = idx)
    }

    private fun ifVisible(step: PlanStep): StepOutcome {
        val q = step.targetHint ?: return StepOutcome(false, notes = "Missing query for IF_VISIBLE")
        val tLbl = step.meta["then"] ?: return StepOutcome(false, notes = "Missing meta.then for IF_VISIBLE")
        val fLbl = step.meta["else"] ?: return StepOutcome(false, notes = "Missing meta.else for IF_VISIBLE")
        val tout = step.meta["timeoutMs"]?.toLongOrNull() ?: 2500L
        val hit = ctx.resolver.isPresentQuick(q, timeoutMs = tout) { }
        val lbl = if (hit != null) tLbl else fLbl
        val idx = ctx.plan.steps.indexOfFirst { it.type == StepType.LABEL && it.targetHint == lbl }
        if (idx < 0) return StepOutcome(false, notes = "IF target not found: $lbl")
        dialog.afterStepSilent(0)
        return StepOutcome(true, nextPc = idx)
    }

    private fun slide(step: PlanStep): StepOutcome {
        val th = step.targetHint ?: return StepOutcome(false, notes = "Missing target for SLIDE")
        ui.ensureVisibleByAutoScrollBounded(
            label = th,
            section = step.meta["section"] ?: ui.parseSectionFromHint(th),
            stepIndex = step.index,
            stepType = StepType.SLIDE,
            maxSwipes = 6
        )
        ctx.resolver.waitForStableUi()
        agent.InputEngine.slideRightByHint(ctx.driver, ctx.resolver, th) { msg -> ctx.onLog("  $msg") }
        dialog.afterStep(step, 2400)
        return StepOutcome(true)
    }
}
