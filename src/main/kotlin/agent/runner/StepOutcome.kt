package agent.runner

import agent.Locator

/**
 * Outcome of a step execution.
 */
data class StepOutcome(
    val ok: Boolean,
    val chosen: Locator? = null,
    val notes: String? = null,
    val advance: Boolean = true,
    val nextPc: Int? = null
)
