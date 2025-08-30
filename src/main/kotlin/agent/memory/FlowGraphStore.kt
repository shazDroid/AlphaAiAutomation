package agent.memory

import agent.StepType
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Persist flow graphs learned from successful runs.
 * Each "flow" is keyed by (appPkg + flowId). A flow contains nodes (step tokens) & edges (transitions with weights).
 * Saved as simple JSON files (no deps) so the UI can read them directly.
 */
class FlowGraphStore(
    baseDir: File = File("graphs")
) {
    private val dir = baseDir.also { it.mkdirs() }

    data class Node(
        val id: String,             // e.g. "INPUT_TEXT:username"
        val label: String,          // pretty label for UI
        var count: Int = 0          // how many times this node observed
    )

    data class Edge(
        val source: String,
        val target: String,
        var weight: Int = 0         // transition count
    )

    data class FlowGraph(
        val id: String,
        val title: String,
        val app: String,
        val activity: String?,
        val nodes: MutableMap<String, Node> = LinkedHashMap(),
        val edges: MutableMap<String, Edge> = LinkedHashMap(),
        var runs: Int = 0,
        var lastSeen: Long = System.currentTimeMillis()
    )

    data class FlowKey(val app: String, val id: String)

    private val graphs = ConcurrentHashMap<FlowKey, FlowGraph>()

    fun normalizeToken(action: StepType, hint: String): String {
        val h = hint.trim().lowercase()
        fun canonField(x: String) = when {
            "user" in x || "email" in x || "login id" in x -> "username"
            "pass" in x -> "password"
            "otp" in x || "one time" in x -> "otp"
            "login" in x || "sign in" in x -> "login"
            "confirm" in x && action == StepType.SLIDE -> "confirm"
            else -> x.replace(Regex("\\s+"), " ").take(32)
        }

        val c = canonField(h)
        return "${action.name}:$c"
    }

    fun addObservation(
        app: String,
        flowId: String,
        title: String,
        activity: String?,
        token: String,
        nextToken: String? = null
    ) {
        val key = FlowKey(app, flowId)
        val g = graphs.getOrPut(key) {
            FlowGraph(id = flowId, title = title, app = app, activity = activity)
        }
        g.lastSeen = System.currentTimeMillis()

        val n = g.nodes.getOrPut(token) { Node(id = token, label = tokenToLabel(token)) }
        n.count += 1

        if (nextToken != null) {
            val eKey = "${token}→${nextToken}"
            val e = g.edges.getOrPut(eKey) { Edge(source = token, target = nextToken) }
            e.weight += 1
        }
    }

    fun bumpRuns(app: String, flowId: String) {
        graphs[FlowKey(app, flowId)]?.let {
            it.runs += 1
            it.lastSeen = System.currentTimeMillis()
        }
    }

    fun saveSnapshot() {
        // index
        val idx = buildString {
            append("{\"updatedAt\":").append(System.currentTimeMillis()).append(",\"flows\":[")
            val all = graphs.values.sortedByDescending { it.lastSeen }
            append(all.joinToString(",") { g ->
                "{\"id\":\"${esc(g.id)}\",\"title\":\"${esc(g.title)}\",\"app\":\"${esc(g.app)}\",\"runs\":${g.runs},\"lastSeen\":${g.lastSeen}}"
            })
            append("]}")
        }
        File(dir, "index.json").writeText(idx)

        // per-flow
        graphs.values.forEach { g ->
            val nodesJson = g.nodes.values.joinToString(",") { n ->
                "{\"id\":\"${esc(n.id)}\",\"label\":\"${esc(n.label)}\",\"count\":${n.count}}"
            }
            val edgesJson = g.edges.values.joinToString(",") { e ->
                "{\"source\":\"${esc(e.source)}\",\"target\":\"${esc(e.target)}\",\"weight\":${e.weight}}"
            }
            val content = "{\"id\":\"${esc(g.id)}\",\"title\":\"${esc(g.title)}\",\"app\":\"${esc(g.app)}\"," +
                    "\"nodes\":[$nodesJson],\"edges\":[$edgesJson],\"runs\":${g.runs},\"lastSeen\":${g.lastSeen}}"
            File(dir, "${g.id}.json").writeText(content)
        }
    }

    // Very light suggestion model from bigrams in edges
    fun suggestNextTokens(app: String, flowId: String?, currentToken: String, topK: Int = 3): List<String> {
        val candidates = if (flowId == null) {
            graphs.filter { it.key.app == app }.values
        } else {
            graphs[FlowKey(app, flowId)]?.let { listOf(it) } ?: emptyList()
        }
        val agg = HashMap<String, Int>()
        candidates.forEach { g ->
            g.edges.values.filter { it.source == currentToken }.forEach { e ->
                agg[e.target] = (agg[e.target] ?: 0) + e.weight
            }
        }
        return agg.entries.sortedByDescending { it.value }.take(topK).map { it.key }
    }

    private fun tokenToLabel(token: String): String =
        token.split(":", limit = 2).let { parts ->
            if (parts.size == 2) "${parts[0].replace('_', ' ')} • ${parts[1]}" else token
        }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
