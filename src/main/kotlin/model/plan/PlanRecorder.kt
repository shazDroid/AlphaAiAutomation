package model.plan

import agent.ActionPlan
import androidx.compose.runtime.snapshots.Snapshot
import java.time.Instant

object PlanRecorder {
    fun recordSuccess(plan: ActionPlan) {
        val p = plan.toPlanAuto(PlanStatus.SUCCESS, Instant.now())
        Snapshot.withMutableSnapshot { PlanRegistry.plans.add(p) }
        PlanStore.saveRegistry()
    }

    fun recordFailure(plan: ActionPlan) {
        val p = plan.toPlanAuto(PlanStatus.FAILED, Instant.now())
        Snapshot.withMutableSnapshot { PlanRegistry.plans.add(p) }
        PlanStore.saveRegistry()
    }
}
