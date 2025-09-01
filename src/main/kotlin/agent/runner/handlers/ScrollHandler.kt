package agent.runner.handlers

import agent.PlanStep
import agent.StepType
import agent.runner.RunContext
import agent.runner.StepOutcome
import agent.runner.services.DialogService
import agent.runner.services.UiService

/**
 * Handles SCROLL_TO.
 */
class ScrollHandler(
    private val ctx: RunContext,
    private val ui: UiService,
    private val dialog: DialogService
) {
    fun scrollTo(step: PlanStep): StepOutcome {
        val th = step.targetHint ?: return StepOutcome(false, notes = "Missing target for SCROLL_TO")
        ui.ensureVisibleByAutoScrollBounded(
            label = th,
            section = step.meta["section"] ?: ui.parseSectionFromHint(th),
            stepIndex = step.index,
            stepType = StepType.SCROLL_TO,
            maxSwipes = 8
        )
        dialog.afterStep(step, 2400)
        return StepOutcome(true)
    }
}
