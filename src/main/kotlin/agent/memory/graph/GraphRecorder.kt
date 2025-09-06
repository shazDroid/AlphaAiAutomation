package agent.memory.graph

import agent.PlanStep
import agent.StepType
import agent.runner.RunContext

class GraphRecorder(private val ctx: RunContext) {
    private var lastNodeId: String? = null
    private val g = GraphStore.load()

    fun begin() {
        lastNodeId = currentNodeId()
        ensureNode(lastNodeId)
    }

    fun record(step: PlanStep, didChangeUi: Boolean, chosenXPath: String?, observedText: String?) {
        val curId = currentNodeId()
        ensureNode(curId)
        if (didChangeUi) {
            val from = lastNodeId ?: curId
            val to = curId
            val edge = GraphEdge(
                from = from,
                to = to,
                actionLabel = labelFor(step),
                locatorXPath = chosenXPath,
                section = step.meta["section"],
                observedText = observedText
            )
            g.edges.add(edge)
            lastNodeId = curId
        } else {
            if (step.type == StepType.ASSERT_TEXT && !observedText.isNullOrBlank()) {
                val from = lastNodeId ?: curId
                val to = curId
                val edge = GraphEdge(
                    from = from,
                    to = to,
                    actionLabel = "assert",
                    locatorXPath = null,
                    section = step.meta["section"],
                    observedText = observedText
                )
                g.edges.add(edge)
            }
        }
    }

    fun end() {
        GraphStore.save(g)
    }

    private fun currentNodeId(): String {
        val act = runCatching { ctx.driver.currentActivity() }.getOrNull()
        val hash = ctx.pageHash()
        return "${act ?: "unknown"}:$hash"
    }

    private fun ensureNode(id: String?) {
        if (id == null) return
        if (!g.nodes.containsKey(id)) {
            val act = runCatching { ctx.driver.currentActivity() }.getOrNull()
            val ph = ctx.pageHash()
            g.nodes[id] = GraphNode(id = id, activity = act, title = act, pageHash = ph)
        }
    }

    private fun labelFor(step: PlanStep): String {
        return when (step.type) {
            StepType.TAP -> step.targetHint ?: "tap"
            StepType.INPUT_TEXT -> "input"
            StepType.WAIT_TEXT -> "wait"
            StepType.ASSERT_TEXT -> "assert"
            else -> step.type.name.lowercase()
        }
    }
}
