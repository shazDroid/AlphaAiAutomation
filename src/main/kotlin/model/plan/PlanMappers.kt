package model.plan

import agent.ActionPlan
import java.time.Instant

fun ActionPlan.toPlan(
    planId: String,
    status: PlanStatus,
    createdAt: Instant,
    stepScreens: StepScreens = emptyMap()
): Plan =
    Plan(
        id = PlanId(planId),
        name = this.title,
        status = status,
        createdAt = createdAt,
        steps = this.steps.map { step ->
            PlanStep(
                index = step.index,
                type = step.type,
                details = buildMap {
                    step.targetHint?.takeIf { it.isNotBlank() }?.let { put("hint", it) }
                    step.value?.takeIf { it.isNotBlank() }?.let { put("value", it) }
                },
                screenshotPath = stepScreens[step.index],
                screen = null
            )
        }
    )

fun ActionPlan.toPlanAuto(
    status: PlanStatus,
    createdAt: Instant,
    stepScreens: StepScreens = emptyMap()
): Plan = toPlan(
    planId = "plan-${this.title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}-${createdAt.toEpochMilli()}",
    status = status,
    createdAt = createdAt,
    stepScreens = stepScreens
)
