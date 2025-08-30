package agent.memory

import agent.Locator
import agent.StepType
import agent.Strategy
import com.fasterxml.jackson.databind.DeserializationFeature
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

    val mapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerKotlinModule()

    private val file = File(dir, "components.json").absoluteFile
    private val data = ConcurrentHashMap<String, Entry>()  // keyId -> Entry

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
        val score get() = successes * 3 - failures * 2

        companion object {
            fun fromLocator(loc: Locator) = MemSelector(loc.strategy, loc.value)
        }
    }

    data class Entry(
        val pkg: String,
        val activity: String,
        val op: StepType,
        val hint: String,
        val selectors: MutableList<MemSelector> = mutableListOf()
    )

    init {
        load()
    }

    fun filePath(): String = file.path

    data class Stats(val entries: Int, val selectors: Int)

    fun stats(): Stats = Stats(
        entries = data.size,
        selectors = data.values.sumOf { it.selectors.size }
    )

    /** Accessors */
    fun get(key: Key): Entry? = data[key.id()]

    fun getSelectors(key: Key): List<MemSelector> {
        val sels = data[key.id()]?.selectors ?: emptyList()
        return sels.toList() // return a copy
    }

    fun markSuccess(key: Key, sel: MemSelector) {
        upsert(key, sel) { it.successes++ }
        save()
    }

    fun markFailure(key: Key, sel: MemSelector) {
        upsert(key, sel) { it.failures++ }
        save()
    }

    private fun upsert(key: Key, sel: MemSelector, mutate: (MemSelector) -> Unit) {
        val id = key.id()
        val e = data.getOrPut(id) { Entry(key.pkg, key.activity, key.op, key.hint) }
        val existing = e.selectors.firstOrNull { it.strategy == sel.strategy && it.value == sel.value }
        if (existing == null) {
            mutate(sel)
            sel.lastSeen = System.currentTimeMillis()
            e.selectors += sel
        } else {
            mutate(existing)
            existing.lastSeen = System.currentTimeMillis()
        }
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val map: Map<String, Entry> = mapper.readValue(file)
            data.clear()
            data.putAll(map)
        }.onFailure { println("memory:load error: ${it.message}") }
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp, data)
            // atomic-ish replace
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        }.onFailure { println("memory:save error: ${it.message}") }
    }

    // ADD: overload that returns a Key for success/failure paths
    fun keyFor(pkg: String, activity: String, op: StepType, hintLower: String): Key =
        Key(pkg = pkg, activity = activity, op = op, hint = hintLower)

    fun keyFor(pkg: String, activity: String, op: String, hintLower: String): String =
        "$pkg||$activity||$op||$hintLower"

    fun getSelectors(key: String): List<MemSelector> =
        (data[key]?.selectors ?: emptyList()).toList()

}

/** Convert stored selector to agent.Locator (strategy is already an enum here). */
fun ComponentMemory.MemSelector.toLocator(): Locator =
    Locator(strategy = this.strategy, value = this.value)
