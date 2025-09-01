package ui.component

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import model.plan.Plan
import model.plan.PlanStatus
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Scrollable plans list with desktop scrollbar and delete-memory actions.
 */
@Composable
fun PlanListPane(
    plans: List<Plan>,
    selectedId: String?,
    onSelect: (Plan) -> Unit,
    modifier: Modifier = Modifier,
    memoryDir: File = File("memory"),
    onMemoryChanged: (() -> Unit)? = null
) {
    val fmt = remember { DateTimeFormatter.ofPattern("dd MMM, HH:mm") }
    val shape = RoundedCornerShape(16.dp)
    val baseBorder = Color(0xFFE0E3EB)
    val baseBg = MaterialTheme.colors.surface
    val selBg = Color(0xFFE8F0FF)
    val selBorder = Color(0xFF6C7CFF)
    var confirmFor by remember { mutableStateOf<Plan?>(null) }
    val listState = rememberLazyListState()

    Box(modifier = modifier.padding(8.dp)) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(plans, key = { it.id.value }) { plan ->
                val selected = (selectedId == plan.id.value)
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
                            text = planDisplayName(plan),
                            style = MaterialTheme.typography.subtitle1.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${plan.steps.size} steps • ${planCreatedAtStr(plan, fmt)}",
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

                    Spacer(Modifier.width(8.dp))

                    IconButton(
                        onClick = { confirmFor = plan },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete memory", tint = Color(0xFFD32F2F))
                    }
                }
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
        )
    }

    if (confirmFor != null) {
        val plan = confirmFor!!
        AlertDialog(
            onDismissRequest = { confirmFor = null },
            title = { Text("Delete memory") },
            text = { Text("Remove selector memory learned for \"${planDisplayName(plan)}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val removed = deleteMemoryForPlanOnDisk(plan, memoryDir = memoryDir, appPkg = null)
                        MemoryBus.saved("Memory deleted • $removed item(s)")
                        onMemoryChanged?.invoke()
                        confirmFor = null
                    }
                ) { Text("Delete", color = Color(0xFFD32F2F)) }
            },
            dismissButton = { TextButton(onClick = { confirmFor = null }) { Text("Cancel") } }
        )
    }
}

private fun planDisplayName(p: Plan): String {
    val t = runCatching { p.javaClass.getMethod("getTitle").invoke(p) as? String }.getOrNull()
        ?.takeIf { !it.isNullOrBlank() }
        ?: runCatching { p.javaClass.getMethod("getName").invoke(p) as? String }.getOrNull()
            ?.takeIf { !it.isNullOrBlank() }
    return t ?: p.id.value
}

private fun planCreatedAtStr(p: Plan, fmt: DateTimeFormatter): String {
    val any = runCatching { p.javaClass.getMethod("getCreatedAt").invoke(p) }.getOrNull()
    return when (any) {
        is java.time.Instant -> any.atZone(ZoneId.systemDefault()).format(fmt)
        is java.time.LocalDateTime -> any.atZone(ZoneId.systemDefault()).format(fmt)
        is Long -> java.time.Instant.ofEpochMilli(any).atZone(ZoneId.systemDefault()).format(fmt)
        else -> ""
    }
}

private val mapper: ObjectMapper = ObjectMapper()
    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .registerKotlinModule()

/**
 * Deletes selector memory lines that correspond to (op,hint) pairs used in the plan.
 */
fun deleteMemoryForPlanOnDisk(
    plan: Plan,
    memoryDir: File = File("memory"),
    appPkg: String? = null
): Int {
    var removed = 0
    val pairs: Set<Pair<String, String>> = plan.steps.mapNotNull { s ->
        val op = runCatching { s.javaClass.getMethod("getType").invoke(s) }.getOrNull()?.toString()
        val hint = runCatching { s.javaClass.getMethod("getTargetHint").invoke(s) as? String }.getOrNull()
            ?: runCatching { s.javaClass.getMethod("getValue").invoke(s) as? String }.getOrNull()
        val h = hint?.trim()?.lowercase()
        if (op.isNullOrBlank() || h.isNullOrBlank()) null else op to h
    }.toSet()
    if (pairs.isEmpty()) return 0

    val jsonFiles = listOf("components.json", "component.json")
        .map { File(memoryDir, it) }
        .filter { it.isFile && it.length() > 0 }

    for (jf in jsonFiles) {
        val root = mapper.readTree(jf)
        if (root !is ObjectNode) continue
        val iter = root.fields()
        val toRemove = mutableListOf<String>()
        while (iter.hasNext()) {
            val (key, node: JsonNode) = iter.next()
            val pkg = node.get("pkg")?.asText().orEmpty()
            val op = node.get("op")?.asText().orEmpty()
            val hint = node.get("hint")?.asText()?.trim()?.lowercase().orEmpty()
            val pkgOk = (appPkg == null) || (pkg == appPkg)
            val pairOk = pairs.any { (st, h) -> st.equals(op, true) && h == hint }
            if (pkgOk && pairOk) toRemove += key
        }
        toRemove.forEach { root.remove(it); removed++ }
        mapper.writerWithDefaultPrettyPrinter().writeValue(jf, root)
    }

    if (memoryDir.isDirectory) {
        memoryDir.listFiles()?.filter { it.isDirectory }?.forEach { appDir ->
            if (appPkg != null && appDir.name != appPkg) return@forEach
            appDir.listFiles()?.filter { it.isDirectory }?.forEach { actDir ->
                actDir.listFiles()
                    ?.filter { it.isFile && it.extension.equals("tsv", true) }
                    ?.forEach { tsv ->
                        val raw = tsv.nameWithoutExtension
                        val us = raw.indexOf('_')
                        val op = (if (us > 0) raw.substring(0, us) else raw).trim()
                        val hint = (if (us > 0) raw.substring(us + 1) else raw)
                            .replace('-', ' ')
                            .trim()
                            .lowercase()
                        val match = pairs.any { (st, h) -> st.equals(op, true) && h == hint }
                        if (match) runCatching { tsv.delete() }.onSuccess { removed++ }
                    }
            }
        }
    }
    return removed
}
