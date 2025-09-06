package agent.recorder

import agent.PlanStep
import agent.StepType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class FlowGraphRecorder(
    private val runId: String,
    private val title: String,
    private val appPkg: String?
) {
    data class Node(
        val id: String,
        val label: String,
        val count: Int = 1,
        val screenTitle: String? = null,
        val activity: String? = null
    )

    data class Edge(val source: String, val target: String, val weight: Int = 1)
    data class FlowGraph(
        val id: String,
        val runId: String,
        val title: String,
        val app: String? = null,
        val nodes: List<Node>,
        val edges: List<Edge>,
        val runs: Int = 1,
        val lastSeen: Long = Instant.now().toEpochMilli()
    )

    private val nodes = ConcurrentHashMap<String, Node>()
    private val edges = ConcurrentHashMap<String, Edge>()
    private var lastNodeId: String? = null

    fun observe(step: PlanStep, activity: String?, screenTitle: String?) {
        val nodeId = toNodeId(step)
        val base = nodes[nodeId]
        val updated = if (base == null) {
            Node(
                id = nodeId,
                label = stepLabel(step),
                count = 1,
                screenTitle = screenTitle,
                activity = activity
            )
        } else {
            base.copy(
                count = base.count + 1,
                screenTitle = base.screenTitle ?: screenTitle,
                activity = base.activity ?: activity
            )
        }
        nodes[nodeId] = updated

        val prev = lastNodeId
        if (!prev.isNullOrBlank() && prev != nodeId) {
            val ek = "$prev->$nodeId"
            val e0 = edges[ek]
            edges[ek] = if (e0 == null) Edge(source = prev, target = nodeId, weight = 1)
            else e0.copy(weight = e0.weight + 1)
        }
        lastNodeId = nodeId
    }

    fun write(outRoot: File): File {
        outRoot.mkdirs()
        val sortedNodes = nodes.values.sortedBy { it.id }
        val sortedEdges = edges.values.sortedWith(compareBy({ it.source }, { it.target }))
        val g = FlowGraph(
            id = "flow-$runId",
            runId = runId,
            title = title,
            app = appPkg,
            nodes = sortedNodes,
            edges = sortedEdges
        )
        val f = outRoot.resolve("${runId}_flow.json")
        jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValue(f, g)
        return f
    }

    private fun toNodeId(s: PlanStep): String {
        val normalizedHint = norm(s.targetHint)
        return when (s.type) {
            StepType.INPUT_TEXT -> "INPUT_TEXT:$normalizedHint"
            StepType.WAIT_TEXT -> "WAIT_TEXT:$normalizedHint"
            StepType.ASSERT_TEXT -> "ASSERT_TEXT:$normalizedHint"
            StepType.TAP -> "TAP:$normalizedHint"
            // Any other enum values (present or future) safely fall back:
            else -> "${s.type.name}:$normalizedHint"
        }
    }

    private fun stepLabel(s: PlanStep): String {
        val hint = s.targetHint?.ifBlank { "" } ?: ""
        return s.type.name + " • " + hint
    }

    private fun norm(s: String?): String =
        (s ?: "").lowercase().trim().replace(Regex("\\s+"), "-")
}
