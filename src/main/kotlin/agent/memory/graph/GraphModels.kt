package agent.memory.graph

data class GraphNode(
    val id: String,
    val activity: String?,
    val title: String?,
    val pageHash: Int
)

data class GraphEdge(
    val from: String,
    val to: String,
    val actionLabel: String,
    val locatorXPath: String?,
    val section: String?,
    val observedText: String?,
    val ts: Long = System.currentTimeMillis()
)

data class GraphMemory(
    val nodes: MutableMap<String, GraphNode> = LinkedHashMap(),
    val edges: MutableList<GraphEdge> = ArrayList()
)
