package agent.ui

import agent.Snapshot
import agent.SnapshotStore
import agent.StepType

// Minimal JSON-ish encoder (no extra deps)
private fun Map<String, String>.toJsonLike(): String =
    if (isEmpty()) ""
    else entries.joinToString(prefix = "{", postfix = "}", separator = ",") {
        val k = it.key.replace("\\", "\\\\").replace("\"", "\\\"")
        val v = it.value.replace("\\", "\\\\").replace("\"", "\\\"")
        "\"$k\":\"$v\""
    }

/** Silent snapshot for gestures; returns the Snapshot if you want it, but does not push to timeline. */
fun SnapshotStore.captureGesture(
    stepIndex: Int,
    kind: String,
    label: String,
    meta: Map<String, String> = emptyMap()
): Snapshot {
    val stepType = when (kind.lowercase()) {
        "scroll" -> StepType.SCROLL_TO
        "slide" -> StepType.SLIDE
        "tap" -> StepType.TAP
        else -> StepType.CHECK
    }

    val notes: String? = meta.takeIf { it.isNotEmpty() }?.toJsonLike()

    // IMPORTANT: match your capture(stepIndex, action, hint, locator, success, notes)
    return capture(stepIndex, stepType, label, null, true, notes)
}

