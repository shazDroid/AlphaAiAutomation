package agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.time.Instant

data class HealedLocator(
    val targetHint: String,
    val strategy: Strategy,
    val value: String,
    val whenSaved: Long = Instant.now().toEpochMilli(),
    val notes: String? = null
)

class SelfHealingLocatorStore(private val file: File = File("runs/locator_vault.json")) {
    private val mapper = jacksonObjectMapper()
    private val index = mutableMapOf<String, MutableList<HealedLocator>>()

    init {
        runCatching {
            if (file.exists()) {
                val list: List<HealedLocator> = mapper.readValue(file.readText(),
                    mapper.typeFactory.constructCollectionType(List::class.java, HealedLocator::class.java))
                list.groupBy { it.targetHint }.forEach { (k, v) -> index[k] = v.toMutableList() }
            }
        }
    }

    fun remember(locator: HealedLocator) {
        val list = index.getOrPut(locator.targetHint) { mutableListOf() }
        if (list.none { it.strategy == locator.strategy && it.value == locator.value }) {
            list += locator
            persist()
        }
    }

    fun suggestionsFor(targetHint: String): List<Locator> =
        index[targetHint].orEmpty().map { Locator(it.strategy, it.value) }

    private fun persist() {
        file.parentFile?.mkdirs()
        val all = index.values.flatten()
        file.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(all))
    }
}
