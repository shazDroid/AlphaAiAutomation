package ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import model.plan.Plan
import model.plan.PlanStatus
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PlanListPane(
    plans: List<Plan>,
    selectedId: String?,
    onSelect: (Plan) -> Unit,
    modifier: Modifier = Modifier
) {
    val fmt = DateTimeFormatter.ofPattern("dd MMM, HH:mm")
    val shape = RoundedCornerShape(16.dp)
    val baseBorder = Color(0xFFE0E3EB)         // same as "All" items
    val baseBg = MaterialTheme.colors.surface
    val selBg = Color(0xFFE8F0FF)              // same light-blue selection bg
    val selBorder = Color(0xFF6C7CFF)          // same blue outline

    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        plans.forEach { plan ->
            val selected = selectedId == plan.id.value

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (selected) selBg else baseBg, shape)
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) selBorder else baseBorder,
                        shape = shape
                    )
                    .clickable { onSelect(plan) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        plan.name,
                        style = MaterialTheme.typography.subtitle1.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${plan.steps.size} steps • " +
                                plan.createdAt.atZone(ZoneId.systemDefault()).format(fmt),
                        style = MaterialTheme.typography.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val statusColor = when (plan.status) {
                    PlanStatus.SUCCESS -> Color(0xFF2E7D32)
                    PlanStatus.PARTIAL -> Color(0xFFF9A825)
                    PlanStatus.FAILED -> Color(0xFFC62828)
                }

                Box(
                    Modifier
                        .height(24.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFFF6F7FB))
                        .border(1.dp, baseBorder, RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = plan.status.name, color = statusColor, style = MaterialTheme.typography.caption)
                }
            }
        }
    }
}
