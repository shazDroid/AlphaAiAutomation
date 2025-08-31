package ui.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import model.plan.Plan
import model.plan.PlanStep

fun planToNodes(plan: Plan): List<Node> {
    val startX = 100f
    val y = 200f
    val gap = 300f
    return plan.steps.mapIndexed { idx, step: PlanStep ->
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
    nodes.zipWithNext().map { (a, b) ->
        Connection(fromNodeId = a.id, fromPortId = "out", toNodeId = b.id, toPortId = "in")
    }
