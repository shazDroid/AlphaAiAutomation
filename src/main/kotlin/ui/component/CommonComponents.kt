package ui.component

import agent.Snapshot
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp

@Composable
fun ChipPill(label: String, value: Int, tint: Color) {
    Box(
        Modifier
            .background(tint.copy(alpha = .12f), RoundedCornerShape(999.dp))
            .border(1.dp, tint.copy(alpha = .35f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(tint, RoundedCornerShape(999.dp)))
            Spacer(Modifier.width(6.dp))
            Text("$label: $value", color = Color(0xFF1D1D1D))
        }
    }
}

@Composable
fun TimelineItem(snap: Snapshot) {
    val ok = snap.success
    val tint = if (ok) Color(0xFF2E7D32) else Color(0xFFD32F2F)
    val glyph = if (ok) "✓" else "✕"

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(18.dp)
                .background(tint.copy(alpha = .15f), RoundedCornerShape(4.dp))
                .border(1.dp, tint.copy(alpha = .4f), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(glyph, color = tint, fontSize = TextUnit(12f, TextUnitType.Sp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "#${snap.stepIndex} ${snap.action} ${snap.targetHint ?: ""}",
            fontWeight = MaterialTheme.typography.h1.fontWeight
        )
    }
}

@Composable
fun RightCard(
    modifier: Modifier = Modifier,
    pad: Dp = 12.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFFE7E9F4), RoundedCornerShape(12.dp))
            .padding(pad)
    ) { content() }
}
