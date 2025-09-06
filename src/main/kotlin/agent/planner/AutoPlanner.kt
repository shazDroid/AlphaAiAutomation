package agent.planner

import agent.*
import agent.memory.graph.GraphEdge
import agent.memory.graph.GraphMemory
import agent.memory.graph.GraphStore
import agent.semantic.SemanticMatcher
import java.util.ArrayDeque
import java.util.ArrayList

object AutoPlanner {
    fun expandFromMemoryIfGoal(plan: ActionPlan): ActionPlan {
        if (plan.steps.isNotEmpty()) return plan
        val goal = plan.title.trim()
        if (goal.isBlank()) return plan
        val g = GraphStore.load()
        val candidates = g.edges.mapNotNull { e ->
            val label = listOfNotNull(e.observedText, e.actionLabel).joinToString(" ").trim()
            if (label.isBlank()) null else label to e
        }
        if (candidates.isEmpty()) return plan
        val ranked = candidates.map { (label, e) ->
            Triple(SemanticMatcher.score(goal, label), label, e)
        }.sortedByDescending { it.first }.take(10)
        val best = ranked.firstOrNull() ?: return plan
        val targetNode = best.third.to
        val path = bfsPath(g, targetNode)
        if (path.isEmpty()) return plan
        val steps = ArrayList<PlanStep>()
        path.forEach { e ->
            if (!e.locatorXPath.isNullOrBlank()) {
                steps += PlanStep(
                    index = steps.size + 1,
                    type = StepType.TAP,
                    targetHint = e.actionLabel,
                    value = null,
                    meta = mutableMapOf("section" to (e.section ?: ""))
                )
            }
        }
        val finalText = best.third.observedText?.takeIf { it.isNotBlank() } ?: goal
        steps += PlanStep(
            index = steps.size + 1,
            type = StepType.ASSERT_TEXT,
            targetHint = finalText,
            value = null,
            meta = mutableMapOf("scrollDir" to "down")
        )
        return plan.copy(steps = reindex(steps))
    }

    private fun bfsPath(g: GraphMemory, target: String): List<GraphEdge> {
        val byFrom = g.edges.groupBy { it.from }
        val startNodes = guessStartNodes(g)
        val prev = HashMap<String, GraphEdge?>()
        val q = ArrayDeque<String>()
        startNodes.forEach { s -> prev[s] = null; q.add(s) }
        var found: String? = null
        while (q.isNotEmpty()) {
            val u = q.removeFirst()
            if (u == target) {
                found = u; break
            }
            val outs = byFrom[u].orEmpty()
            for (e in outs) {
                if (!prev.containsKey(e.to)) {
                    prev[e.to] = e
                    q.add(e.to)
                }
            }
        }
        if (found == null) return emptyList()
        val edges = ArrayList<GraphEdge>()
        var cur = found
        while (true) {
            val pe = prev[cur] ?: break
            edges.add(pe)
            cur = pe.from
        }
        edges.reverse()
        return edges
    }

    private fun guessStartNodes(g: GraphMemory): List<String> {
        val ins = g.edges.groupBy { it.to }.mapValues { it.value.size }
        val outs = g.edges.groupBy { it.from }.mapValues { it.value.size }
        val roots = outs.keys.filter { (ins[it] ?: 0) == 0 }.ifEmpty { outs.keys.toList() }
        return if (roots.isEmpty()) g.nodes.keys.toList() else roots
    }

    private fun reindex(steps: List<PlanStep>): List<PlanStep> =
        steps.mapIndexed { i, p -> p.copy(index = i + 1) }
}
