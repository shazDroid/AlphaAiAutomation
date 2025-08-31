package model.plan


import agent.ActionPlan
import java.time.Instant
import java.util.Locale

private fun autoPlanId(name: String, createdAt: Instant): String {
    val base = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
    return "plan-${base}-${createdAt.toEpochMilli()}"
}

fun ActionPlan.toPlan(planId: String, status: PlanStatus, createdAt: Instant): Plan =
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
                screenshotPath = null,
                screen = null
            )
        }
    )

fun ActionPlan.toPlanAuto(status: PlanStatus, createdAt: Instant): Plan =
    toPlan(autoPlanId(this.title, createdAt), status, createdAt)
