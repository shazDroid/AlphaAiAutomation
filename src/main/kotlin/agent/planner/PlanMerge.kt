package agent.planner

import agent.ActionPlan
import agent.PlanStep
import agent.StepType

object PlanMerge {
    fun mergeGraphThenUser(graph: ActionPlan?, user: ActionPlan): ActionPlan {
        val g = graph?.steps.orEmpty().toMutableList()
        val u = user.steps
        val out = ArrayList<PlanStep>()
        val seen = LinkedHashSet<String>()

        fun key(t: PlanStep) = t.type.name + "|" + norm(t.targetHint)

        g.forEach { s ->
            out += s
            seen += key(s)
        }

        u.forEach { s ->
            val k = key(s)
            val existingIdx = out.indexOfFirst {
                key(it) == k ||
                        (s.type == StepType.INPUT_TEXT && norm(it.targetHint) == norm(s.targetHint)) ||
                        (s.type == StepType.WAIT_TEXT && it.type == StepType.TAP && norm(it.targetHint) == "wait")
            }
            if (existingIdx >= 0) {
                val cur = out[existingIdx]
                val upgraded = when {
                    s.type == StepType.INPUT_TEXT && cur.type != StepType.INPUT_TEXT -> s
                    s.type == StepType.WAIT_TEXT && cur.type != StepType.WAIT_TEXT -> s
                    else -> s
                }
                out[existingIdx] = upgraded
                seen += key(upgraded)
            } else if (!seen.contains(k)) {
                out += s
                seen += k
            }
        }

        val fixed = out.mapIndexed { i, p -> p.copy(index = i + 1) }
        val title = when {
            !graph?.title.isNullOrBlank() -> graph!!.title
            user.title.isNotBlank() -> user.title
            else -> "AutoRun"
        }
        return ActionPlan(title = title, steps = fixed)
    }

    private fun norm(s: String?): String =
        (s ?: "").lowercase().trim().replace(Regex("\\s+"), "-")
}
