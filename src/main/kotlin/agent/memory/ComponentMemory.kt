package agent.memory

import agent.Locator
import agent.StepType
import agent.Strategy
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple, JSON-backed selector memory with self-heal scoring.
 * Keyed by (pkg, activity, op, hint). Stores multiple selectors with success/failure counts.
 */
class ComponentMemory(dir: File) {

    data class Key(val pkg: String, val activity: String, val op: StepType, val hint: String) {
        fun id(): String = listOf(pkg, activity, op.name, hint.lowercase()).joinToString("||")
    }

    data class MemSelector(
        val strategy: Strategy,
        val value: String,
        var successes: Int = 0,
        var failures: Int = 0,
        var lastSeen: Long = System.currentTimeMillis()
    ) {
        val score: Int
            get() = successes * 3 - failures * 2

        companion object {
            fun fromLocator(loc: Locator): MemSelector =
                MemSelector(strategy = loc.strategy, value = loc.value)
        }
    }

    data class Entry(
        val pkg: String,
        val activity: String,
        val op: StepType,
        val hint: String,
        val selectors: MutableList<MemSelector> = mutableListOf()
    ) {
        fun key(): Key = Key(pkg, activity, op, hint)
    }

    data class Stats(val entries: Int, val selectors: Int)

    private val mapper = ObjectMapper().registerKotlinModule()
    private val root = File(dir, "components.json").apply { parentFile.mkdirs(); if (!exists()) writeText("{}") }
    private val mem: ConcurrentHashMap<String, Entry> = ConcurrentHashMap(loadAll())

    private fun loadAll(): Map<String, Entry> =
        runCatching { mapper.readValue<Map<String, Entry>>(root) }.getOrElse { emptyMap() }

    private fun persist() {
        runCatching { mapper.writerWithDefaultPrettyPrinter().writeValue(root, mem) }
    }

    fun keyFor(pkg: String, activity: String?, op: StepType, hint: String): Key =
        Key(pkg = pkg.trim(), activity = (activity ?: "").trim(), op = op, hint = hint.trim())

    fun bestFor(key: Key): MemSelector? =
        mem[key.id()]?.selectors?.maxByOrNull { it.score }

    fun markSuccess(key: Key, sel: MemSelector) {
        val id = key.id()
        val e = mem.getOrPut(id) { Entry(key.pkg, key.activity, key.op, key.hint) }
        val existing = e.selectors.firstOrNull { it.strategy == sel.strategy && it.value == sel.value }
        if (existing == null) {
            sel.successes = 1; sel.failures = 0; sel.lastSeen = System.currentTimeMillis()
            e.selectors.add(sel)
        } else {
            existing.successes += 1; existing.lastSeen = System.currentTimeMillis()
        }
        // keep top 6 by score to avoid bloat
        e.selectors.sortByDescending { it.score }
        while (e.selectors.size > 6) e.selectors.removeLast()
        persist()
    }

    fun markFailure(key: Key, sel: MemSelector) {
        val id = key.id()
        val e = mem.getOrPut(id) { Entry(key.pkg, key.activity, key.op, key.hint) }
        val existing = e.selectors.firstOrNull { it.strategy == sel.strategy && it.value == sel.value }
        if (existing == null) {
            sel.failures = 1; sel.lastSeen = System.currentTimeMillis()
            e.selectors.add(sel)
        } else {
            existing.failures += 1; existing.lastSeen = System.currentTimeMillis()
        }
        // prune very bad selectors
        e.selectors.removeAll { it.failures >= 3 && it.score < 0 }
        persist()
    }

    fun stats(): Stats {
        val entries = mem.size
        val selectors = mem.values.sumOf { it.selectors.size }
        return Stats(entries, selectors)
    }

    fun listEntries(): List<Entry> = mem.values.sortedWith(
        compareBy<Entry>({ it.pkg }, { it.activity }, { it.op.name }, { it.hint.lowercase() })
    )

    fun get(key: Key): Entry? = mem[key.id()]
}
