package agent.memory

import agent.StepType

class FlowRecorder(
    private val store: FlowGraphStore,
    private val appPkg: String,
    private val flowId: String,
    private val title: String,
    private val activityProvider: () -> String?
) {
    private val tokens = mutableListOf<String>()
    private var committed = false

    fun addStep(action: StepType, hint: String) {
        val token = store.normalizeToken(action, hint)
        tokens += token
        // Add node and local bigram (token-1 -> token)
        val prev = tokens.dropLast(1).lastOrNull()
        store.addObservation(
            app = appPkg,
            flowId = flowId,
            title = title,
            activity = activityProvider(),
            token = token,
            nextToken = null
        )
        if (prev != null) {
            store.addObservation(
                app = appPkg,
                flowId = flowId,
                title = title,
                activity = activityProvider(),
                token = prev,
                nextToken = token
            )
        }
    }

    fun commitRun() {
        if (!committed) {
            store.bumpRuns(app = appPkg, flowId = flowId)
            store.saveSnapshot()
            committed = true
        }
    }
}
