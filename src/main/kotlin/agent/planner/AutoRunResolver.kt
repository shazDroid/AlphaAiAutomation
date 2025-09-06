package agent.planner

import agent.ActionPlan
import agent.semantic.DefaultFieldResolver
import agent.semantic.FieldResolver
import java.io.File

object AutoRunResolver {
    fun resolve(
        goal: String,
        userPlan: ActionPlan?,
        runsDir: File,
        enableAutoRun: Boolean,
        log: (String) -> Unit,
        fieldResolver: FieldResolver = DefaultFieldResolver
    ): ActionPlan {
        val quick = QuickIntent.parse(goal, fieldResolver)
        val baseUser = if (userPlan == null || userPlan.steps.isEmpty()) quick.copy(title = goal) else userPlan
        if (!enableAutoRun) return reindex(baseUser)

        log("autorun:resolve goal=\"$goal\" enableAutoRun=$enableAutoRun")

        val fromLatest = RunGraphAdapter.planFromLatestRun(goal, runsDir, log)
            ?: RunGraphAdapter.planFromLatestRun(goal, File("runs"), log)

        val fromMemory = AutoPlanner.expandFromMemoryIfGoal(ActionPlan(title = goal, steps = emptyList()))
        val graphPlan = fromLatest ?: fromMemory

        val aligned = PlanAligner.align(graphPlan, baseUser)
        log("autorun:resolved graphSteps=${graphPlan?.steps?.size ?: 0} userSteps=${baseUser.steps.size} outSteps=${aligned.steps.size}")
        return aligned
    }

    private fun reindex(plan: ActionPlan): ActionPlan =
        plan.copy(steps = plan.steps.mapIndexed { i, s -> s.copy(index = i + 1) })
}
