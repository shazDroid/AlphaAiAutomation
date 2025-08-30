package agent.memory

import agent.StepType

// simple DTO you can persist alongside your graph snapshots if you want
data class FlowStepDTO(
    val type: StepType,
    val title: String,
    val body: String? = null
)

/**
 * Records step tokens + light bigrams so FlowGraphStore can build edges,
 * and (optionally) keeps a human-friendly step list (title/body).
 *
 * Backwards compatible:
 *  - addStep(type, hint)   // old call sites keep working
 *  - addStep(type, title, body) // new, richer call (used by AgentRunner)
 */
class FlowRecorder(
    private val store: FlowGraphStore,
    private val appPkg: String,
    private val flowId: String,
    private val title: String,
    private val activityProvider: () -> String?
) {
    // token stream used to build edges
    private val tokens = mutableListOf<String>()

    // optional human-readable steps list (useful for later UI/export)
    private val steps = mutableListOf<FlowStepDTO>()

    private var committed = false

    /** Old API kept for compatibility. */
    fun addStep(action: StepType, hint: String) {
        addStep(action, hint, null)
    }

    /** New API with a body line (e.g., INPUT → "amount: 500"). */
    fun addStep(type: StepType, title: String, body: String?) {
        val token = store.normalizeToken(type, title)
        val prev = tokens.lastOrNull()

        tokens += token
        steps += FlowStepDTO(type = type, title = title, body = body?.takeIf { it.isNotBlank() })

        // Node and local bigram
        store.addObservation(
            app = appPkg,
            flowId = flowId,
            title = this.title,
            activity = activityProvider(),
            token = token,
            nextToken = null
        )
        if (prev != null) {
            store.addObservation(
                app = appPkg,
                flowId = flowId,
                title = this.title,
                activity = activityProvider(),
                token = prev,
                nextToken = token
            )
        }
        saveNow()
    }

    /** Call once per run; AgentRunner now does this at the end. */
    fun commitRun() {
        if (!committed) {
            store.bumpRuns(app = appPkg, flowId = flowId)
            saveNow()
            committed = true
        }
    }

    /** Small helper to flush store if the implementation persists snapshots. */
    private fun saveNow() {
        runCatching { store.saveSnapshot() }
    }
}
