package ui.component

import agent.memory.MemEntry
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MemoryEntryCard(
    e: MemEntry,
    selected: Boolean,
    onSelect: (MemEntry) -> Unit
) {
    val bg = if (selected) Color(0xFFE8F0FF) else Color.White
    val border = if (selected) Color(0xFF6A7BFF) else Color(0xFFE6E6E6)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .background(bg, RoundedCornerShape(12.dp))
            .clickable { onSelect(e) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            // Title: app + activity (single line)
            Text(
                text = "${e.appPkg} • ${e.activity.substringAfterLast('.')}",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            // Body: op -> hint (single line)
            Text(
                text = "${e.op} → ${e.hint}",
                color = Color(0xFF666666),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        SelectorCountChip(e.selectors.size)
    }
}

@Composable
private fun SelectorCountChip(count: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, Color(0xFFD7DDF0), RoundedCornerShape(999.dp))
            .background(Color(0xFFF4F6FD), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "$count sel", fontSize = 11.sp, color = Color(0xFF334155))
    }
}
