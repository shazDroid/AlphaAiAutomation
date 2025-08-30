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
import androidx.compose.ui.unit.*
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/* ================== Data ================== */

data class Node(
    val id: String,
    val position: Offset,
    val title: String,
    val color: Color,
    val inputs: List<Port>,
    val outputs: List<Port>,
    val body: String = "…"
)

data class Port(val id: String, val nodeId: String, val type: PortType)
enum class PortType { INPUT, OUTPUT }

data class Connection(
    val fromNodeId: String,
    val fromPortId: String,
    val toNodeId: String,
    val toPortId: String
)

fun autoConnect(nodes: List<Node>): List<Connection> =
    nodes.windowed(2).mapNotNull { (a, b) ->
        val from = a.outputs.firstOrNull()
        val to = b.inputs.firstOrNull()
        if (from != null && to != null) Connection(a.id, from.id, b.id, to.id) else null
    }

/* =============== Editor =============== */

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NodeGraphEditor(
    nodes: List<Node>,
    connections: List<Connection>,
    onNodePositionChange: (nodeId: String, dragAmount: Offset) -> Unit,
    onNewConnection: (Connection) -> Unit,
    modifier: Modifier = Modifier
) {
    var canvasOffset by remember { mutableStateOf(Offset.Zero) }
    var scale by remember { mutableStateOf(1f) }

    var connectionDragState by remember { mutableStateOf<ConnectionDragState?>(null) }
    val portPositions = remember { mutableStateMapOf<String, Offset>() }
    val allPorts = remember(nodes) { nodes.flatMap { it.inputs + it.outputs } }

    val density = LocalDensity.current
    var editorOrigin by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }      // editor size in px
    val nodeSizes = remember { mutableStateMapOf<String, IntSize>() } // node sizes in px
    val marginPx = with(density) { 12.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA))
            .clipToBounds()                                        // NEW: clip everything to editor
            .onGloballyPositioned {
                editorOrigin = it.positionInRoot()
                viewport = it.size
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.type == PointerEventType.Scroll) {
                            val d = ev.changes.first().scrollDelta.y
                            val newScale = (scale - d * 0.1f).coerceIn(0.2f, 3f)
                            val p = ev.changes.first().position
                            val ratio = newScale / scale
                            canvasOffset = p * (1 - ratio) + canvasOffset * ratio
                            scale = newScale
                            ev.changes.first().consume()
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    canvasOffset += drag
                }
            }
    ) {
        // grid
        Canvas(Modifier.fillMaxSize()) {
            // grid spacing in PX that scales with zoom
            val base = 20.dp.toPx()
            val step = (base * scale).coerceAtLeast(8f) // avoid sub-pixel clutter when zoomed out

            // anchor the pattern to the camera so it always fills the viewport
            fun posMod(a: Float, b: Float): Float = ((a % b) + b) % b
            val startX = posMod(-canvasOffset.x, step)
            val startY = posMod(-canvasOffset.y, step)

            var x = startX
            while (x < size.width) {
                var y = startY
                while (y < size.height) {
                    drawCircle(
                        color = Color.LightGray.copy(alpha = 0.5f),
                        radius = 2f,
                        center = Offset(x, y)
                    )
                    y += step
                }
                x += step
            }
        }


        // connections
        Canvas(Modifier.fillMaxSize().clipToBounds()) {           // NEW: clip wires
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
                offset = node.position * scale + canvasOffset,     // screen coords
                scale = scale,
                onMeasured = { nodeSizes[node.id] = it },
                onPositionChange = { dragScreen ->
                    val size = nodeSizes[node.id]
                    if (size == null || viewport == IntSize.Zero) {
                        // no measure yet → just move by the drag, no clamp
                        onNodePositionChange(node.id, dragScreen / scale)
                    } else {
                        // clamp in SCREEN space, then convert back to world
                        val curScreen = worldToScreen(node.position, canvasOffset, scale)
                        val wantedScreen = curScreen + dragScreen
                        val clampedScreen = clampScreen(wantedScreen, size, viewport, marginPx)
                        val newWorld = screenToWorld(clampedScreen, canvasOffset, scale)
                        val deltaWorld = newWorld - node.position
                        if (deltaWorld != Offset.Zero) onNodePositionChange(node.id, deltaWorld)
                    }
                },
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
                        val end = allPorts.find { p ->
                            portPositions["${p.nodeId}_${p.id}"]?.let { pp ->
                                (st.currentPosition - pp).getDistance() < 20.dp.toPx(density)
                            } == true
                        }
                        if (end != null && st.startPort.type != end.type && st.startPort.nodeId != end.nodeId) {
                            val newConn =
                                if (st.startPort.type == PortType.OUTPUT)
                                    Connection(st.startPort.nodeId, st.startPort.id, end.nodeId, end.id)
                                else
                                    Connection(end.nodeId, end.id, st.startPort.nodeId, st.startPort.id)
                            onNewConnection(newConn)
                        }
                    }
                    connectionDragState = null
                }
            )
        }

        // zoom controls
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { scale = (scale * 1.2f).coerceIn(0.2f, 3f) }) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Zoom In")
            }
            Button(onClick = { scale = (scale / 1.2f).coerceIn(0.2f, 3f) }) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Zoom Out")
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
    onMeasured: (IntSize) -> Unit,
    onPositionChange: (Offset) -> Unit,
    onPortPositioned: (Port, Offset) -> Unit,
    onConnectionDragStart: (Port) -> Unit,
    onConnectionDrag: (Offset) -> Unit,
    onConnectionDragEnd: () -> Unit
) {
    Box(
        modifier = Modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .onGloballyPositioned { onMeasured(it.size) }
            .width((240 * scale).dp)
            .shadow((8 * scale).dp, RoundedCornerShape((12 * scale).dp))
            .background(Color.White, RoundedCornerShape((12 * scale).dp))
            .pointerInput(node.id) {
                detectDragGestures { change, dragAmount ->
                    change.consume(); onPositionChange(dragAmount)
                }
            }
    ) {
        Column {
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

            Row(Modifier.fillMaxWidth().padding(vertical = (12 * scale).dp)) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy((16 * scale).dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    node.inputs.forEach { port ->
                        PortHandle(
                            port,
                            scale,
                            onPortPositioned,
                            onConnectionDragStart,
                            onConnectionDrag,
                            onConnectionDragEnd
                        )
                    }
                }

                Box(Modifier.weight(3f).height((48 * scale).dp)) {
                    Text(
                        node.body,
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.Center),
                        fontSize = (14 * scale).sp
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy((16 * scale).dp),
                    horizontalAlignment = Alignment.End
                ) {
                    node.outputs.forEach { port ->
                        PortHandle(
                            port,
                            scale,
                            onPortPositioned,
                            onConnectionDragStart,
                            onConnectionDrag,
                            onConnectionDragEnd
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
    onDragEnd: () -> Unit
) {
    Box(
        modifier = Modifier
            .size((24 * scale).dp)
            .onGloballyPositioned { lc ->
                val p = lc.positionInRoot()
                val center = Offset(p.x + lc.size.width / 2, p.y + lc.size.height / 2)
                onPositioned(port, center)
            }
            .pointerInput(port.id) {
                detectDragGestures(onDragStart = { onDragStart(port) }, onDragEnd = { onDragEnd() }) { change, drag ->
                    change.consume(); onDrag(drag)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size((16 * scale).dp).clip(CircleShape).background(Color.LightGray.copy(alpha = .6f)))
    }
}

/* =============== Drawing helpers =============== */

private fun DrawScope.drawGrid(gridSize: Float, color: Color, scale: Float) {
    val w = size.width / scale + gridSize
    val h = size.height / scale + gridSize
    val step = gridSize.toInt()
    for (x in 0..w.toInt() step step)
        for (y in 0..h.toInt() step step)
            drawCircle(color, radius = 2f, center = Offset(x.toFloat(), y.toFloat()))
}

private fun DrawScope.drawConnection(start: Offset, end: Offset, color: Color = Color(0xFFFD9644)) {
    val path = Path().apply {
        moveTo(start.x, start.y)
        val d = ((end.x - start.x) / 2f).coerceAtLeast(100f)
        cubicTo(start.x + d, start.y, end.x - d, end.y, end.x, end.y)
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
            Node(
                "n_app",
                Offset(100f, 250f),
                "Sample App",
                Color(0xFFF9A825),
                emptyList(),
                listOf(Port("out", "n_app", PortType.OUTPUT))
            ),
            Node(
                "n_screen", Offset(400f, 250f), "Sample Screen", Color(0xFFD6C6E1),
                listOf(Port("in", "n_screen", PortType.INPUT)), listOf(Port("out", "n_screen", PortType.OUTPUT))
            )
        )
    }
    val connections = remember(nodes) { mutableStateListOf<Connection>().also { it.addAll(autoConnect(nodes)) } }

    Box(Modifier.size(1200.dp, 800.dp)) {
        NodeGraphEditor(
            nodes, connections,
            onNodePositionChange = { id, d ->
                nodes.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { i ->
                    nodes[i] = nodes[i].copy(position = nodes[i].position + d)
                }
            },
            onNewConnection = { new ->
                if (connections.none { it.fromNodeId == new.fromNodeId && it.fromPortId == new.fromPortId && it.toNodeId == new.toNodeId && it.toPortId == new.toPortId })
                    connections.add(new)
            }
        )
    }
}

private fun worldToScreen(world: Offset, canvasOffsetPx: Offset, scale: Float): Offset =
    world * scale + canvasOffsetPx

private fun screenToWorld(screen: Offset, canvasOffsetPx: Offset, scale: Float): Offset =
    (screen - canvasOffsetPx) / scale

private fun clampScreen(
    posScreen: Offset,
    nodeSizePx: IntSize,
    viewportPx: IntSize,
    marginPx: Float
): Offset {
    if (viewportPx == IntSize.Zero) return posScreen
    val minX = marginPx
    val minY = marginPx
    val maxX = viewportPx.width - nodeSizePx.width - marginPx
    val maxY = viewportPx.height - nodeSizePx.height - marginPx
    return Offset(
        x = posScreen.x.coerceIn(minX, maxX),
        y = posScreen.y.coerceIn(minY, maxY)
    )
}

