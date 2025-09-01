package agent.runner.services

import agent.Locator
import agent.StepType
import agent.runner.RunContext
import agent.util.isGenericSelector

/**
 * Memory adapter wrapper.
 */
class MemoryService(private val ctx: RunContext) {
    fun find(op: StepType, hint: String?): List<Locator> {
        val mem = ctx.memory ?: return emptyList()
        val pkg = pkg()
        val act = act()
        val acts = aliases(act, pkg) + listOf(null, "")
        val hits = mutableListOf<Locator>()
        val h = hint?.trim()?.lowercase()
        for (a in acts) runCatching { mem.find(pkg, a, op, h) }.onSuccess { if (it.isNotEmpty()) hits += it }
        return hits.distinctBy { it.strategy to it.value }.filterNot { isGenericSelector(it.strategy.name, it.value) }
    }

    fun save(op: StepType, hint: String?, chosen: Locator, prior: Locator?) {
        val mem = ctx.memory ?: return
        val pkg = pkg()
        val act = act()
        val h = hint?.trim()?.lowercase()
        if (isGenericSelector(chosen.strategy.name, chosen.value)) {
            ctx.memoryEvent("Skipped generic selector for \"${h ?: op.name}\"")
            return
        }
        mem.success(pkg, act, op, h, chosen)
        if (prior != null && (prior.strategy != chosen.strategy || prior.value != chosen.value)) {
            if (!isGenericSelector(prior.strategy.name, prior.value)) mem.failure(pkg, act, op, h, prior)
        }
    }

    private fun pkg(): String = runCatching { ctx.driver.currentPackage }.getOrNull().orEmpty()
    private fun act(): String = ctx.currentActivitySafe()

    private fun aliases(raw: String, pkg: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val r = raw.trim()
        val base = r.removePrefix(".").substringAfterLast('.')
        val full = if (r.startsWith(".")) "$pkg${r}" else r
        val fullFromBase = if (base.isNotBlank()) "$pkg.$base" else ""
        return listOfNotNull(
            r.takeIf { it.isNotBlank() },
            full.takeIf { it.isNotBlank() },
            base.takeIf { it.isNotBlank() },
            fullFromBase.takeIf { it.isNotBlank() && it != full }).distinct()
    }
}
