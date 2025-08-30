package agent.memory

import agent.Locator
import agent.SelectorMemory
import agent.StepType
import java.util.Locale

/**
 * Adapter that lets AgentRunner use ComponentMemory as a SelectorMemory.
 * Tries simple activity aliases (".X", "X", "pkg.X") and also "" (no activity).
 */
class ComponentMemoryAdapter(private val mem: ComponentMemory) : SelectorMemory {

    private fun norm(s: String?): String = s?.trim()?.lowercase(Locale.ROOT).orEmpty()

    override fun find(appPkg: String, activity: String?, op: StepType, hint: String?): List<Locator> {
        val h = norm(hint)

        // try: exact activity, aliases, then (null/"")
        val acts: List<String?> = activityAliases(activity, appPkg) + listOf(null, "")

        // Build typed Keys (not strings)
        val keys: List<ComponentMemory.Key> = acts.map { a ->
            mem.keyFor(
                pkg = appPkg,
                activity = (a ?: "").trim(),
                op = op,
                hintLower = h
            )
        }

        // Map stored selectors to agent.Locator; de-dup by (strategy,value)
        return keys
            .flatMap { k -> mem.getSelectors(k).map { sel -> sel.toLocator() } }
            .distinctBy { it.strategy to it.value }
    }

    override fun success(appPkg: String, activity: String?, op: StepType, hint: String?, locator: Locator) {
        val h = norm(hint)
        if (h.isEmpty()) return

        val key = mem.keyFor(
            pkg = appPkg,
            activity = (activity ?: "").trim(),
            op = op,
            hintLower = h
        )
        mem.markSuccess(key, ComponentMemory.MemSelector.fromLocator(locator))
    }

    override fun failure(appPkg: String, activity: String?, op: StepType, hint: String?, prior: Locator) {
        val h = norm(hint)
        if (h.isEmpty()) return

        val key = mem.keyFor(
            pkg = appPkg,
            activity = (activity ?: "").trim(),
            op = op,
            hintLower = h
        )
        mem.markFailure(key, ComponentMemory.MemSelector.fromLocator(prior))
    }

    // Keep this in sync with AgentRunner.activityAliases
    private fun activityAliases(raw: String?, pkg: String): List<String> {
        val r = (raw ?: "").trim()
        if (r.isBlank()) return emptyList()
        val base = r.removePrefix(".").substringAfterLast('.')
        val full = if (r.startsWith(".")) "$pkg$r" else r
        val fullFromBase = if (base.isNotBlank()) "$pkg.$base" else ""
        return listOfNotNull(
            r.takeIf { it.isNotBlank() },
            full.takeIf { it.isNotBlank() },
            base.takeIf { it.isNotBlank() },
            fullFromBase.takeIf { it.isNotBlank() && it != full }
        ).distinct()
    }
}
