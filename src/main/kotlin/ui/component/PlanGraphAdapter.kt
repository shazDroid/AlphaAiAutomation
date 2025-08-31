package ui.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import model.plan.Plan
import model.plan.PlanRunIndex
import model.plan.collectStepScreensFromRun
import model.plan.runsRoot
import java.io.File

fun planToNodes(plan: Plan): List<Node> {
    val startX = 100f
    val y = 200f
    val gap = 300f
    return plan.steps.mapIndexed { idx, step ->
        val nodeId = "plan_${plan.id.value}_${step.index}"
        Node(
            id = nodeId,
            position = Offset(startX + idx * gap, y),
            title = step.type.name,
            color = Color(0xFF90CAF9),
            inputs = if (idx == 0) emptyList() else listOf(Port(id = "in", nodeId = nodeId, type = PortType.INPUT)),
            outputs = if (idx == plan.steps.lastIndex) emptyList() else listOf(
                Port(
                    id = "out",
                    nodeId = nodeId,
                    type = PortType.OUTPUT
                )
            ),
            body = step.details.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        )
    }
}

fun planToConnections(nodes: List<Node>): List<Connection> =
    nodes.zipWithNext().map { (a, b) -> Connection(a.id, "out", b.id, "in") }

fun planScreenshotMap(plan: Plan): Map<String, String> {
    val runId = PlanRunIndex.get(plan.id.value)
    val indexToPath: Map<Int, String> = runId
        ?.let { File(runsRoot(), it) }
        ?.takeIf { it.exists() }
        ?.let { collectStepScreensFromRun(it) }
        ?: emptyMap()

    return plan.steps.associate { st ->
        val p = indexToPath[st.index] ?: st.screenshotPath.orEmpty()
        "plan_${plan.id.value}_${st.index}" to if (p.isNotBlank() && File(p).exists()) p else ""
    }.filterValues { it.isNotBlank() }
}
