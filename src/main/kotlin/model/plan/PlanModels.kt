package model.plan

import agent.StepType
import java.time.Instant

enum class PlanStatus { SUCCESS, FAILED, PARTIAL }

data class PlanId(val value: String)

data class PlanStep(
    val index: Int,
    val type: StepType,
    val details: Map<String, String> = emptyMap(),
    val screenshotPath: String? = null,
    val screen: String? = null
)

data class Plan(
    val id: PlanId,
    val name: String,
    val status: PlanStatus,
    val createdAt: Instant,
    val steps: List<PlanStep>
)
