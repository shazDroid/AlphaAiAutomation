package agent.memory.graph

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

object GraphStore {
    private val mapper = jacksonObjectMapper()
    private fun file(): File {
        val home = System.getProperty("user.home")
        val dir = File(home, ".alpha-ui-automation")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "graph.json")
    }

    fun load(): GraphMemory {
        val f = file()
        if (!f.exists()) return GraphMemory()
        return runCatching { mapper.readValue<GraphMemory>(f) }.getOrElse { GraphMemory() }
    }

    fun save(g: GraphMemory) {
        val f = file()
        runCatching { mapper.writerWithDefaultPrettyPrinter().writeValue(f, g) }
    }
}
