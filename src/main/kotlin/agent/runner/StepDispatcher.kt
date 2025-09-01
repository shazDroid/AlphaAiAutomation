package agent.runner

import agent.PlanStep
import agent.StepType
import agent.runner.handlers.*

/**
 * Dispatches steps to concrete handlers.
 */
class StepDispatcher(
    private val tap: TapHandler,
    private val input: InputHandler,
    private val toggle: ToggleHandler,
    private val assertWait: AssertAndWaitHandler,
    private val scroll: ScrollHandler,
    private val nav: NavHandler
) {
    fun execute(step: PlanStep, pc: Int): StepOutcome {
        return when (step.type) {
            StepType.LAUNCH_APP -> nav.launch(step)
            StepType.INPUT_TEXT -> input.input(step)
            StepType.TAP -> tap.tap(step)
            StepType.CHECK -> toggle.check(step)
            StepType.WAIT_TEXT, StepType.WAIT_OTP, StepType.ASSERT_TEXT -> assertWait.handle(step)
            StepType.SCROLL_TO -> scroll.scrollTo(step)
            StepType.BACK, StepType.SLEEP, StepType.LABEL, StepType.GOTO, StepType.IF_VISIBLE, StepType.SLIDE -> nav.handle(
                step,
                pc
            )

            else -> StepOutcome(true)
        }
    }
}
