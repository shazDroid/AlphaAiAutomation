package model.plan

import agent.ActionPlan
import androidx.compose.runtime.snapshots.Snapshot
import java.io.File
import java.time.Instant
import java.util.Locale

private fun slugify(s: String) =
    s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')

private fun computePlanId(title: String, at: Instant) =
    "plan-${slugify(title)}-${at.toEpochMilli()}"

object PlanRecorder {
    fun recordSuccess(plan: ActionPlan, runDir: File? = null) {
        val now = Instant.now()
        val planId = computePlanId(plan.title, now)
        val run = runDir ?: latestRunDir()
        run?.name?.let { PlanRunIndex.put(planId, it) }

        val p = plan.toPlan(
            planId = planId,
            status = PlanStatus.SUCCESS,
            createdAt = now,
            stepScreens = emptyMap() // we resolve from runs/<runId> on demand
        )
        Snapshot.withMutableSnapshot { PlanRegistry.plans.add(p) }
        PlanStore.saveRegistry()
    }

    fun recordFailure(plan: ActionPlan, runDir: File? = null) {
        val now = Instant.now()
        val planId = computePlanId(plan.title, now)
        val run = runDir ?: latestRunDir()
        run?.name?.let { PlanRunIndex.put(planId, it) }

        val p = plan.toPlan(
            planId = planId,
            status = PlanStatus.FAILED,
            createdAt = now,
            stepScreens = emptyMap()
        )
        Snapshot.withMutableSnapshot { PlanRegistry.plans.add(p) }
        PlanStore.saveRegistry()
    }
}
