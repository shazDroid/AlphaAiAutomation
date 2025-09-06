package agent.planner

import agent.ActionPlan
import agent.PlanStep
import agent.StepType
import agent.semantic.DefaultFieldResolver
import agent.semantic.FieldResolver

object QuickIntent {
    fun parse(goal: String): ActionPlan = parse(goal, DefaultFieldResolver)

    fun parse(goal: String, resolver: FieldResolver): ActionPlan {
        val steps = mutableListOf<PlanStep>()
        val g = goal.lowercase()

        Regex("""\b(input|enter|type)\s+(?:"([^"]+)"|([a-z0-9_ -]+?))\s+"([^"]+)"""")
            .findAll(g).forEach { m ->
                val rawField = (m.groupValues[2].ifBlank { m.groupValues[3] }).trim()
                val value = m.groupValues[4]
                val hint = resolver.resolve(goal, rawField)
                steps += PlanStep(
                    index = 0,
                    type = StepType.INPUT_TEXT,
                    targetHint = hint,
                    value = value,
                    meta = mutableMapOf()
                )
            }

        Regex("""\bwait\s+"([^"]+)"""")
            .findAll(g).forEach { m ->
                val txt = m.groupValues[1].trim()
                steps += PlanStep(
                    index = 0,
                    type = StepType.WAIT_TEXT,
                    targetHint = txt,
                    value = null,
                    meta = mutableMapOf("scrollDir" to "down")
                )
            }

        Regex("""\b(tap|click|press)\s+"([^"]+)"""")
            .findAll(g).forEach { m ->
                val label = m.groupValues[2].trim()
                steps += PlanStep(
                    index = 0,
                    type = StepType.TAP,
                    targetHint = label,
                    value = null,
                    meta = mutableMapOf()
                )
            }

        return ActionPlan(title = goal, steps = steps.mapIndexed { i, p -> p.copy(index = i + 1) })
    }
}
