package agent

object AutoRunEntry {
    fun goalOnlyPlan(goal: String): ActionPlan = ActionPlan(
        title = goal.ifBlank { "AutoRun" },
        steps = emptyList()
    )
}
