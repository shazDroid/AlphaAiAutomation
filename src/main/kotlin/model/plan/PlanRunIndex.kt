package model.plan

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object PlanRunIndex {
    private val mapper = ObjectMapper().registerKotlinModule()
    private val path: Path = Path.of(System.getProperty("user.home"), ".alpha-ui-automation", "plan_runs.json")
    private var index: MutableMap<String, String> = mutableMapOf()

    fun load() {
        index = if (Files.exists(path)) mapper.readValue(Files.readAllBytes(path)) else mutableMapOf()
    }

    fun save() {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling("plan_runs.json.tmp")
        Files.write(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(index))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun put(planId: String, runId: String) {
        index[planId] = runId; save()
    }

    fun get(planId: String): String? = index[planId]
}
