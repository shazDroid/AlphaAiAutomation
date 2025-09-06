package agent.planner

import agent.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.util.ArrayDeque
import java.util.ArrayList

object RunGraphAdapter {

    data class FlowGraph(
        val id: String,
        val title: String,
        val app: String? = null,
        val nodes: List<Node>,
        val edges: List<Edge>,
        val runs: Int? = null,
        val lastSeen: Long? = null
    ) {
        data class Node(val id: String, val label: String, val count: Int = 1)
        data class Edge(val source: String, val target: String, val weight: Int = 1)
    }

    private val mapper = jacksonObjectMapper()

    fun planFromLatestRun(goal: String, runsDir: File, log: (String) -> Unit = {}): ActionPlan? {
        log("graph-infer: scanning dir=$runsDir goal=\"$goal\"")
        val candidateFiles = runsDir
            .walkTopDown()
            .maxDepth(3)
            .filter { it.isFile && it.extension.lowercase() == "json" && it.name.endsWith("_flow.json") }
            .toList()
        if (candidateFiles.isEmpty()) {
            log("graph-infer: no json files found")
            return null
        }
        val latest = candidateFiles.maxByOrNull { it.lastModified() } ?: return null
        log("graph-infer: latestGraph=${latest.absolutePath}")

        val graph = runCatching { mapper.readValue<FlowGraph>(latest) }.getOrNull()
        if (graph == null || graph.nodes.isEmpty() || graph.edges.isEmpty()) {
            log("graph-infer: failed to parse ${latest.name}")
            return null
        }

        val assertNodes = graph.nodes.filter { it.id.startsWith("ASSERT_TEXT:", true) }
        log("graph-infer: assertNodes=${assertNodes.size}")
        if (assertNodes.isEmpty()) return null

        val scored = assertNodes.map { n ->
            val text = n.label.substringAfter("•").trim()
            val score = simpleScore(goal, text)
            Triple(score, text, n)
        }.sortedByDescending { it.first }

        log("graph-infer: topScores=" + scored.take(5).joinToString { "%.2f".format(it.first) + "->" + it.second })

        val best = scored.firstOrNull() ?: return null
        log("graph-infer: chosenTargetId=${best.third.id} text=\"${best.second}\" score=%.2f".format(best.first))

        val path = bfsPath(graph, best.third.id)
        if (path == null || path.isEmpty()) {
            log("graph-infer: bfs no path")
            return null
        }
        log("graph-infer: path=" + path.joinToString(" -> "))

        val steps = mutableListOf<PlanStep>()
        for (nodeId in path) {
            when {
                nodeId.startsWith("TAP:", true) -> {
                    val hint = nodeId.substringAfter("TAP:", "").replace('-', ' ').trim()
                    steps += PlanStep(
                        index = steps.size + 1,
                        type = StepType.TAP,
                        targetHint = hint,
                        value = null,
                        meta = mutableMapOf(
                            "__fromGraph" to "1",
                            "graphFile" to latest.absolutePath,
                            "graphNode" to nodeId
                        )
                    )
                }

                nodeId.startsWith("WAIT_TEXT:", true) -> {
                    val hint = nodeId.substringAfter("WAIT_TEXT:", "").replace('-', ' ').trim()
                    steps += PlanStep(
                        index = steps.size + 1,
                        type = StepType.WAIT_TEXT,
                        targetHint = hint,
                        value = null,
                        meta = mutableMapOf(
                            "__fromGraph" to "1",
                            "graphFile" to latest.absolutePath,
                            "graphNode" to nodeId,
                            "scrollDir" to "down"
                        )
                    )
                }

                nodeId.startsWith("INPUT_TEXT:", true) -> {
                    val hint = nodeId.substringAfter("INPUT_TEXT:", "").replace('-', ' ').trim()
                    steps += PlanStep(
                        index = steps.size + 1,
                        type = StepType.INPUT_TEXT,
                        targetHint = hint,
                        value = null,
                        meta = mutableMapOf(
                            "__fromGraph" to "1",
                            "graphFile" to latest.absolutePath,
                            "graphNode" to nodeId
                        )
                    )
                }
            }
        }
        steps += PlanStep(
            index = steps.size + 1,
            type = StepType.ASSERT_TEXT,
            targetHint = best.second,
            value = null,
            meta = mutableMapOf(
                "__fromGraph" to "1",
                "graphFile" to latest.absolutePath,
                "graphNode" to best.third.id,
                "scrollDir" to "down"
            )
        )

        log("graph-infer: emittedSteps=" + steps.joinToString { "${it.index}:${it.type}:${it.targetHint}" })
        return ActionPlan(title = "Auto: $goal", steps = reindex(steps))
    }

    private fun bfsPath(g: FlowGraph, targetNodeId: String): List<String>? {
        val outs = g.edges.groupBy { it.source }
        val ins = g.edges.groupBy { it.target }
        val roots = g.nodes.map { it.id }.filter { ins[it].isNullOrEmpty() }.ifEmpty { g.nodes.map { it.id } }
        val prev = HashMap<String, String?>()
        val q = ArrayDeque<String>()
        roots.forEach { r -> prev[r] = null; q.add(r) }
        var found: String? = null
        while (q.isNotEmpty()) {
            val u = q.removeFirst()
            if (u == targetNodeId) {
                found = u; break
            }
            for (e in outs[u].orEmpty().sortedByDescending { it.weight }) {
                if (!prev.containsKey(e.target)) {
                    prev[e.target] = u
                    q.add(e.target)
                }
            }
        }
        if (found == null) return null
        val seq = ArrayList<String>()
        var cur: String? = found
        while (cur != null) {
            seq.add(cur); cur = prev[cur]
        }
        seq.reverse()
        return seq
    }

    private fun simpleScore(a: String, b: String): Double {
        fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9\\s]+"), " ").replace(Regex("\\s+"), " ").trim()
        val ta = norm(a).split(" ").filter { it.length >= 2 }.toSet()
        val tb = norm(b).split(" ").filter { it.length >= 2 }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.intersect(tb).size.toDouble()
        val denom = (ta.size + tb.size - inter).coerceAtLeast(1.0).toDouble()
        return inter / denom
    }

    private fun reindex(steps: List<PlanStep>): List<PlanStep> =
        steps.mapIndexed { i, p -> p.copy(index = i + 1) }
}
