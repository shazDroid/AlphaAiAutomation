package agent.memory

import agent.Locator
import agent.SelectorMemory
import agent.StepType
import agent.Strategy
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight, file-backed memory for selectors.
 * Stores lines: pkg||activity||op||hint||strategy||value
 */
class SimpleSelectorMemory(private val file: File) : SelectorMemory {

    private data class Key(val pkg: String, val activity: String?, val op: StepType, val hint: String?)

    private val map = ConcurrentHashMap<Key, MutableList<Locator>>()
    private val lock = Any()

    init {
        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        if (file.exists()) load()
    }

    override fun find(appPkg: String, activity: String?, op: StepType, hint: String?): List<Locator> {
        val k = Key(appPkg, activity?.ifBlank { null }, op, hint?.ifBlank { null })
        val k2 = Key(appPkg, null, op, hint?.ifBlank { null }) // fallback: no-activity
        val direct = map[k].orEmpty()
        val fallback = map[k2].orEmpty()
        // return unique by (strategy,value)
        return (direct + fallback).distinctBy { it.strategy to it.value }
    }

    override fun success(appPkg: String, activity: String?, op: StepType, hint: String?, locator: Locator) {
        val k = Key(appPkg, activity?.ifBlank { null }, op, hint?.ifBlank { null })
        val list = map.getOrPut(k) { mutableListOf() }
        // move to front (MRU), de-dupe by (strategy,value)
        list.removeAll { it.strategy == locator.strategy && it.value == locator.value }
        list.add(0, locator)
        // keep short list
        while (list.size > 5) list.removeLast()
        save()
    }

    override fun failure(appPkg: String, activity: String?, op: StepType, hint: String?, prior: Locator) {
        val k = Key(appPkg, activity?.ifBlank { null }, op, hint?.ifBlank { null })
        val list = map[k] ?: return
        // push it to the back (least preferred) or drop it
        list.removeAll { it.strategy == prior.strategy && it.value == prior.value }
        list.add(prior) // comment this line if you prefer to *remove* bad entries entirely
        save()
    }

    private fun load() = synchronized(lock) {
        file.forEachLine { line ->
            val parts = line.split("||")
            if (parts.size < 6) return@forEachLine
            val pkg = parts[0]
            val act = parts[1].ifBlank { null }
            val op = runCatching { StepType.valueOf(parts[2]) }.getOrNull() ?: return@forEachLine
            val hint = parts[3].ifBlank { null }
            val strategy = runCatching { Strategy.valueOf(parts[4]) }.getOrNull() ?: Strategy.XPATH
            val value = parts[5]
            val k = Key(pkg, act, op, hint)
            val list = map.getOrPut(k) { mutableListOf() }
            list += Locator(strategy, value)
        }
    }

    private fun save() = synchronized(lock) {
        val tmp = StringBuilder()
        map.forEach { (k, list) ->
            list.forEach { loc ->
                tmp.append(k.pkg).append("||")
                    .append(k.activity.orEmpty()).append("||")
                    .append(k.op.name).append("||")
                    .append(k.hint.orEmpty()).append("||")
                    .append(loc.strategy.name).append("||")
                    .append(loc.value).append("\n")
            }
        }
        file.writeText(tmp.toString())
    }
}
