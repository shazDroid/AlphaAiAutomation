package ui.component

import agent.Snapshot
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.LinearProgressIndicator
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

@Composable
fun GenerationStatusCard(
    isGenerating: Boolean,
    step: Int,
    total: Int,
    message: String,
    error: String?,
    outDir: java.io.File?
) {
    val pct = (step.toFloat().coerceAtLeast(0f) / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    val bg = when {
        error != null -> Color(0xFFFFF2F2)
        !isGenerating && outDir != null -> Color(0xFFE9FFF1)
        else -> Color(0xffffffff)
    }
    val border = when {
        error != null -> Color(0xFFFFC7C7)
        !isGenerating && outDir != null -> Color(0xFFBFEBD0)
        else -> Color(0xFFDDE6FF)
    }

    Box(
        Modifier.fillMaxWidth()
            .background(bg, RoundedCornerShape(10.dp))
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Column {
            if (isGenerating) {
                Text("Generating scripts…", fontWeight = MaterialTheme.typography.h6.fontWeight)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = pct, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(message, color = Color(0xFF5A6BFF))
                Text(
                    "${(pct * 100).toInt()}% · step $step of $total",
                    color = Color.Gray,
                    fontSize = TextUnit(12f, TextUnitType.Sp)
                )
            } else if (error != null) {
                Text(
                    "Generation failed",
                    color = Color(0xFFB00020),
                    fontWeight = MaterialTheme.typography.h6.fontWeight
                )
                Spacer(Modifier.height(6.dp))
                SelectionContainer { Text(error, color = Color(0xFF7A0000)) }
            } else if (outDir != null) {
                Text(
                    "Scripts generated",
                    color = Color(0xFF137333),
                    fontWeight = MaterialTheme.typography.h6.fontWeight
                )
                Spacer(Modifier.height(6.dp))
                Text(outDir.absolutePath, color = Color(0xFF1F5F3E))
                Spacer(Modifier.height(8.dp))
                AlphaButton(text = "Open folder") {
                    try {
                        java.awt.Desktop.getDesktop().open(outDir)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }
}

