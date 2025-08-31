package ui.component

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt


// add near the top of the file (after data classes)
private const val NODE_W_WORLD = 260f
private const val NODE_H_WORLD = 520f

private data class FRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

private fun contentBounds(
    nodes: List<Node>,
    sizeWorld: Map<String, Pair<Float, Float>>
): FRect {
    if (nodes.isEmpty()) return FRect(0f, 0f, 1f, 1f)
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    nodes.forEach { n ->
        val (w, h) = sizeWorld[n.id] ?: (NODE_W_WORLD to NODE_H_WORLD)
        minX = minOf(minX, n.position.x)
        minY = minOf(minY, n.position.y)
        maxX = maxOf(maxX, n.position.x + w)
        maxY = maxOf(maxY, n.position.y + h)
    }
    return FRect(minX, minY, maxX, maxY)
}




/* ================== Data ================== */

data class Node(
    val id: String,
    val position: Offset,      // world-space position you control
    val title: String,
    val color: Color,
    val inputs: List<Port>,
    val outputs: List<Port>,
    val body: String = "…"
)

data class Port(
    val id: String,
    val nodeId: String,
    val type: PortType
)

enum class PortType { INPUT, OUTPUT }

data class Connection(
    val fromNodeId: String,
    val fromPortId: String,
    val toNodeId: String,
    val toPortId: String
)

/** Connects each node to the next, using the first OUTPUT→first INPUT. */
fun autoConnect(nodes: List<Node>): List<Connection> =
    nodes.windowed(2).mapNotNull { (a, b) ->
        val fromPort = a.outputs.firstOrNull()
        val toPort = b.inputs.firstOrNull()
        if (fromPort != null && toPort != null)
            Connection(a.id, fromPort.id, b.id, toPort.id)
        else null
    }

/**
 * Auto-arrange nodes into a left→right layered layout with a gentle staircase.
 * Keeps your API the same: call autoArrangeNodes(nodes, connections).
 */
fun autoArrangeNodes(
    nodes: MutableList<Node>,
    connections: List<Connection>,
    startX: Float = 180f,
    startY: Float = 120f,
    colGap: Float = 320f,   // horizontal spacing between columns
    rowGap: Float = 170f,   // vertical spacing between nodes in the same column
    diagStep: Float = 90f   // extra Y added per column to create the staircase
) {
    if (nodes.isEmpty()) return

    val idx = nodes.indices.associateBy { nodes[it].id }
    val out = connections.groupBy { it.fromNodeId }.mapValues { it.value.map { c -> c.toNodeId } }
    val indeg = mutableMapOf<String, Int>().apply { nodes.forEach { this[it.id] = 0 } }
    connections.forEach { indeg[it.toNodeId] = (indeg[it.toNodeId] ?: 0) + 1 }

    // Kahn-style layering; if no true source, pick the left-most node as a source
    val sources = nodes.map { it.id }.filter { (indeg[it] ?: 0) == 0 }
        .ifEmpty { listOf(nodes.minBy { it.position.x }.id) }

    val level = mutableMapOf<String, Int>()
    val q = ArrayDeque<String>().apply { sources.forEach { level[it] = 0; add(it) } }

    while (q.isNotEmpty()) {
        val u = q.removeFirst()
        val lu = level[u] ?: 0
        for (v in out[u].orEmpty()) {
            val cur = level[v]
            if (cur == null || lu + 1 < cur) level[v] = lu + 1
            val left = (indeg[v] ?: 1) - 1
            indeg[v] = left
            if (left == 0) q += v
        }
    }
    // Unreached nodes get level 0
    nodes.forEach { level.putIfAbsent(it.id, 0) }

    // Place by column; each column gets a slight +Y shift (diagStep) to create the staircase
    val byCol = level.entries.groupBy({ it.value }, { it.key }).toSortedMap()
    byCol.forEach { (col, ids) ->
        val baseX = startX + col * colGap
        var y = startY + col * diagStep
        ids.sortedBy { idx[it] }.forEach { id ->
            val i = idx[id] ?: return@forEach
            nodes[i] = nodes[i].copy(position = Offset(baseX, y))
            y += rowGap
        }
    }
}


/* =============== Editor =============== */

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NodeGraphEditor(
    nodes: List<Node>,
    connections: List<Connection>,
    onNodePositionChange: (nodeId: String, dragAmount: Offset) -> Unit,
    onNewConnection: (Connection) -> Unit,
    modifier: Modifier = Modifier,
    onAutoArrange: (() -> Unit)? = null,
    graphKey: Any? = null,
    screenshotPathForNodeId: ((String) -> String?)? = null
) {
    // ——— camera & transient state (reset per graphKey) ———
    var canvasOffset by remember(graphKey) { mutableStateOf(Offset.Zero) }
    var scale by remember(graphKey) { mutableStateOf(1f) }
    var connectionDragState by remember(graphKey) { mutableStateOf<ConnectionDragState?>(null) }
    val portPositions = remember(graphKey) { mutableStateMapOf<String, Offset>() }
    var isDraggingNode by remember(graphKey) { mutableStateOf(false) }

    // editor geometry
    var editorOrigin by remember { mutableStateOf(Offset.Zero) }
    var editorSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val density = LocalDensity.current

    val nodeSizesWorld = remember(graphKey) { mutableStateMapOf<String, Pair<Float, Float>>() }


    fun clampCameraToContent(paddingPx: Float = 24f) {
        if (editorSize.width == 0 || editorSize.height == 0) return
        val b = contentBounds(nodes, nodeSizesWorld)
        val viewW = editorSize.width.toFloat()
        val viewH = editorSize.height.toFloat()

        val minOffsetX = viewW - paddingPx - b.right * scale
        val maxOffsetX = paddingPx - b.left * scale
        val minOffsetY = viewH - paddingPx - b.bottom * scale
        val maxOffsetY = paddingPx - b.top * scale

        val withinX = minOffsetX <= maxOffsetX
        val withinY = minOffsetY <= maxOffsetY

        canvasOffset = if (withinX && withinY) {
            Offset(
                canvasOffset.x.coerceIn(minOffsetX, maxOffsetX),
                canvasOffset.y.coerceIn(minOffsetY, maxOffsetY)
            )
        } else {
            val centerWorld = Offset(b.left + b.width / 2f, b.top + b.height / 2f)
            val centerView = Offset(viewW / 2f, viewH / 2f)
            centerView - centerWorld * scale
        }
    }


    // helper: compute a nice camera that fits all nodes in view
    fun zoomToFit(paddingPx: Float = 80f) {
        if (nodes.isEmpty() || editorSize.width == 0 || editorSize.height == 0) return
        val b = contentBounds(nodes, nodeSizesWorld)
        val viewW = editorSize.width.toFloat() - paddingPx * 2
        val viewH = editorSize.height.toFloat() - paddingPx * 2
        val s = minOf(
            viewW / b.width.coerceAtLeast(1f),
            viewH / b.height.coerceAtLeast(1f)
        ).coerceIn(0.2f, 3f)
        scale = if (s.isFinite() && s > 0f) s else 1f
        val centerWorld = Offset(b.left + b.width / 2f, b.top + b.height / 2f)
        val centerView = Offset(editorSize.width / 2f, editorSize.height / 2f)
        canvasOffset = centerView - centerWorld * scale
        clampCameraToContent()
    }


    // auto-fit whenever a new graph is selected or size changes
    LaunchedEffect(graphKey, editorSize) { zoomToFit() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(Color(0xFFF8F9FA))
            .onGloballyPositioned {
                editorOrigin = it.positionInRoot()
                editorSize = it.size
            }
            // zoom with wheel
            .pointerInput(graphKey, scale, nodes) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val dy = event.changes.first().scrollDelta.y
                            val newScale = ((scale - dy * 0.08f).coerceIn(0.2f, 3f) * 100f).roundToInt() / 100f
                            val p = event.changes.first().position
                            val ratio = newScale / scale
                            canvasOffset = p * (1 - ratio) + canvasOffset * ratio
                            scale = newScale
                            clampCameraToContent()
                            event.changes.first().consume()
                        }
                    }
                }
            }
            // pan with drag (but not while dragging a node)
            .pointerInput(graphKey, isDraggingNode, nodes) {
                detectDragGestures { change, drag ->
                    if (!isDraggingNode) {
                        change.consume()
                        canvasOffset += drag
                        clampCameraToContent()
                    }
                }
            }
    ) {
        // dotted grid (screen-space)
        Canvas(Modifier.fillMaxSize()) {
            val base = 20.dp.toPx()
            val step = (base * scale).coerceAtLeast(8f)
            fun posMod(a: Float, b: Float) = ((a % b) + b) % b
            val startX = posMod(-canvasOffset.x, step)
            val startY = posMod(-canvasOffset.y, step)
            var x = startX
            while (x < size.width) {
                var y = startY
                while (y < size.height) {
                    drawCircle(Color.LightGray.copy(alpha = 0.5f), radius = 2f, center = Offset(x, y))
                    y += step
                }
                x += step
            }
        }

        // wires
        Canvas(Modifier.fillMaxSize()) {
            connections.forEach { c ->
                val s = portPositions["${c.fromNodeId}_${c.fromPortId}"]
                val e = portPositions["${c.toNodeId}_${c.toPortId}"]
                if (s != null && e != null) drawConnection(s, e)
            }
            connectionDragState?.let { st ->
                val s = portPositions["${st.startPort.nodeId}_${st.startPort.id}"]
                if (s != null) drawConnection(s, st.currentPosition, Color(0xFF673AB7))
            }
        }

        // nodes
        nodes.forEach { node ->
            GraphNode(
                node = node,
                offset = node.position * scale + canvasOffset,
                scale = scale,
                onDragStart = { isDraggingNode = true },
                onDragEnd = { isDraggingNode = false },
                onPositionChange = { drag -> onNodePositionChange(node.id, drag / scale) },
                onPortPositioned = { port, posInRoot ->
                    portPositions["${port.nodeId}_${port.id}"] = posInRoot - editorOrigin
                },
                onConnectionDragStart = { port ->
                    portPositions["${port.nodeId}_${port.id}"]?.let {
                        connectionDragState = ConnectionDragState(port, it)
                    }
                },
                onConnectionDrag = { drag ->
                    connectionDragState =
                        connectionDragState?.copy(currentPosition = connectionDragState!!.currentPosition + drag)
                },
                onConnectionDragEnd = {
                    connectionDragState?.let { st ->
                        val endPort = (nodes.flatMap { it.inputs + it.outputs }).find { port ->
                            portPositions["${port.nodeId}_${port.id}"]?.let { p ->
                                (st.currentPosition - p).getDistance() < 20.dp.toPx(density)
                            } == true
                        }
                        if (endPort != null && st.startPort.type != endPort.type && st.startPort.nodeId != endPort.nodeId) {
                            val newConn =
                                if (st.startPort.type == PortType.OUTPUT)
                                    Connection(st.startPort.nodeId, st.startPort.id, endPort.nodeId, endPort.id)
                                else
                                    Connection(endPort.nodeId, endPort.id, st.startPort.nodeId, st.startPort.id)
                            onNewConnection(newConn)
                        }
                    }
                    connectionDragState = null
                },
                graphKey = graphKey,
                screenshotPathForNodeId = screenshotPathForNodeId,
                onMeasured = { id, wPx, hPx ->
                    nodeSizesWorld[id] = (wPx / scale) to (hPx / scale)
                    clampCameraToContent()
                }
            )
        }

        // zoom controls + auto
        Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scale = (scale * 1.2f).coerceIn(0.2f, 3f) }) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Zoom In")
            }
            Button(onClick = { scale = (scale / 1.2f).coerceIn(0.2f, 3f) }) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Zoom Out")
            }
            if (onAutoArrange != null) {
                Button(onClick = { onAutoArrange(); zoomToFit() }) { Text("Auto") }  // fit after arrange
            }
        }
    }
}

private data class ConnectionDragState(val startPort: Port, val currentPosition: Offset)

/* =============== Node & Port =============== */

@Composable
private fun GraphNode(
    node: Node,
    offset: Offset,
    scale: Float,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onPositionChange: (Offset) -> Unit,
    onPortPositioned: (Port, Offset) -> Unit,
    onConnectionDragStart: (Port) -> Unit,
    onConnectionDrag: (Offset) -> Unit,
    onConnectionDragEnd: () -> Unit,
    graphKey: Any? = null,
    screenshotPathForNodeId: ((String) -> String?)? = null,
    onMeasured: (String, Float, Float) -> Unit
) {
    Box(
        modifier = Modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .width((NODE_W_WORLD * scale).dp)
            .onGloballyPositioned { lc ->
                onMeasured(node.id, lc.size.width.toFloat(), lc.size.height.toFloat())
            }
            .shadow((8 * scale).dp, RoundedCornerShape((12 * scale).dp))
            .background(Color.White, RoundedCornerShape((12 * scale).dp))
            .pointerInput(graphKey, node.id) { /* unchanged drag code */ }
    ) {
        Column {
            // header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(node.color, RoundedCornerShape(topStart = (12 * scale).dp, topEnd = (12 * scale).dp))
                    .padding((8 * scale).dp)
            ) {
                Text(
                    text = node.title,
                    color = if (node.color.luminance() < 0.5f) Color.White else Color.Black.copy(alpha = 0.85f),
                    fontWeight = FontWeight.Bold,
                    fontSize = (16 * scale).sp
                )
            }

            // 1) replace the Row(...) inside GraphNode with:
            Row(Modifier.fillMaxWidth().padding(vertical = (12 * scale).dp)) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy((16 * scale).dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    node.inputs.forEach { port ->
                        PortHandle(
                            port, scale,
                            onPortPositioned,
                            onConnectionDragStart,
                            onConnectionDrag,
                            onConnectionDragEnd,
                            graphKey
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(3f).padding(horizontal = (8 * scale).dp),
                    verticalArrangement = Arrangement.spacedBy((8 * scale).dp)
                ) {
                    if (node.body.isNotBlank()) {
                        Text(
                            node.body,
                            color = Color.Gray,
                            fontSize = (14 * scale).sp
                        )
                    }
                    val thumbPath = screenshotPathForNodeId?.invoke(node.id)
                    if (!thumbPath.isNullOrBlank()) {
                        NodeThumbnail(thumbPath)
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy((16 * scale).dp),
                    horizontalAlignment = Alignment.End
                ) {
                    node.outputs.forEach { port ->
                        PortHandle(
                            port, scale,
                            onPortPositioned,
                            onConnectionDragStart,
                            onConnectionDrag,
                            onConnectionDragEnd,
                            graphKey
                        )
                    }
                }
            }

        }
    }
}

@Composable
private fun PortHandle(
    port: Port,
    scale: Float,
    onPositioned: (Port, Offset) -> Unit,
    onDragStart: (Port) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    graphKey: Any? = null
) {

    Box(
        modifier = Modifier
            .size((24 * scale).dp)
            .onGloballyPositioned { lc ->
                val p = lc.positionInRoot()
                val center = Offset(p.x + lc.size.width / 2, p.y + lc.size.height / 2)
                onPositioned(port, center) // report in root; editor converts to local
            }
            .pointerInput(graphKey, port.id) {
                detectDragGestures(
                    onDragStart = { onDragStart(port) },
                    onDragEnd = { onDragEnd() }
                ) { change, drag ->
                    change.consume()
                    onDrag(drag)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size((16 * scale).dp).clip(CircleShape).background(Color.LightGray.copy(alpha = .6f)))
    }
}

/* =============== Drawing helpers =============== */

private fun DrawScope.drawConnection(start: Offset, end: Offset, color: Color = Color(0xFFFD9644)) {
    val path = Path().apply {
        moveTo(start.x, start.y)
        val c = ((end.x - start.x) / 2f).coerceAtLeast(100f)
        cubicTo(start.x + c, start.y, end.x - c, end.y, end.x, end.y)
    }
    drawPath(path, color = color, style = Stroke(width = 3.dp.toPx()))
}

private fun Offset.getDistance(): Float = sqrt(x.pow(2) + y.pow(2))
private fun Dp.toPx(density: Density): Float = with(density) { this@toPx.toPx() }

/* =============== Preview =============== */

@Preview
@Composable
fun NodeGraphEditorPreview() {
    val nodes = remember {
        mutableStateListOf(
            Node("A", Offset(0f, 0f), "App", Color(0xFFF9A825), emptyList(), listOf(Port("o", "A", PortType.OUTPUT))),
            Node(
                "B",
                Offset(0f, 0f),
                "Screen",
                Color(0xFFD6C6E1),
                listOf(Port("i", "B", PortType.INPUT)),
                listOf(Port("o", "B", PortType.OUTPUT))
            ),
            Node(
                "C",
                Offset(0f, 0f),
                "Op",
                Color(0xFFC5CAE9),
                listOf(Port("i", "C", PortType.INPUT)),
                listOf(Port("o", "C", PortType.OUTPUT))
            ),
            Node(
                "D",
                Offset(0f, 0f),
                "Hint",
                Color(0xFFC8E6C9),
                listOf(Port("i", "D", PortType.INPUT)),
                listOf(Port("o", "D", PortType.OUTPUT))
            ),
            Node(
                "E",
                Offset(0f, 0f),
                "Selector",
                Color(0xFFFFF3E0),
                listOf(Port("i", "E", PortType.INPUT)),
                emptyList()
            )
        )
    }
    val connections = remember(nodes) {
        mutableStateListOf<Connection>().also { it.addAll(autoConnect(nodes)) }
    }

    LaunchedEffect(Unit) { autoArrangeNodes(nodes, connections) }

    Box(Modifier.size(1200.dp, 800.dp)) {
        NodeGraphEditor(
            nodes = nodes,
            connections = connections,
            onNodePositionChange = { id, drag ->
                val i = nodes.indexOfFirst { it.id == id }
                if (i != -1) nodes[i] = nodes[i].copy(position = nodes[i].position + drag)
            },
            onNewConnection = { new ->
                if (connections.none { it.fromNodeId == new.fromNodeId && it.fromPortId == new.fromPortId && it.toNodeId == new.toNodeId && it.toPortId == new.toPortId }) {
                    connections.add(new)
                }
            },
            onAutoArrange = { autoArrangeNodes(nodes, connections) }
        )
    }
}
