package ui.component

import androidx.compose.ui.geometry.Offset
import kotlin.math.floor
import kotlin.math.max

fun gridArrangeToFit(
    nodes: MutableList<Node>,
    maxWidthDp: Float,
    startX: Float = 120f,
    startY: Float = 140f,
    colGap: Float = 360f,
    rowGap: Float = 220f,
    minCols: Int = 1
) {
    if (nodes.isEmpty()) return
    val usable = max(0f, maxWidthDp - startX * 2)
    val cols = max(minCols, floor(usable / colGap).toInt().coerceAtLeast(1))
    var col = 0
    var row = 0
    for (i in nodes.indices) {
        val x = startX + col * colGap
        val y = startY + row * rowGap
        nodes[i] = nodes[i].copy(position = Offset(x, y))
        col++
        if (col >= cols) {
            col = 0; row++
        }
    }
}
