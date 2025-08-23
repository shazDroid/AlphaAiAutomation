package agent

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class ActionPlan(
    val title: String = "",
    val steps: List<PlanStep> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlanStep(
    val index: Int = 0,
    val type: StepType = StepType.TAP,
    val targetHint: String? = null,
    val value: String? = null,
    val meta: Map<String, String> = emptyMap()
)

enum class StepType { LAUNCH_APP, TAP, INPUT_TEXT, SCROLL_TO, WAIT_TEXT, ASSERT_TEXT, BACK, SLEEP,WAIT_OTP,SLIDE, LABEL,GOTO, IF_VISIBLE, CHECK     }
