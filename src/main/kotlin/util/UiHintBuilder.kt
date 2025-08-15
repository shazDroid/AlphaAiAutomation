// util/UiHintsBuilder.kt
package util

import agent.Snapshot
import org.jsoup.Jsoup
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File

object UiHintsBuilder {
    private val mapper = jacksonObjectMapper().registerKotlinModule()

    fun extractHints(timeline: List<Snapshot>): String {
        val all = mutableListOf<Map<String, String>>()
        timeline.forEach { snap ->
            val xml = File(snap.pageSourcePath).takeIf { it.exists() }?.readText() ?: return@forEach
            val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
            doc.select("node").forEach { n ->
                val id = n.attr("resource-id")
                val text = n.attr("text")
                val desc = n.attr("content-desc")
                val clazz = n.attr("class")
                if (id.isNotBlank() || text.isNotBlank() || desc.isNotBlank()) {
                    all += mapOf("id" to id, "text" to text, "desc" to desc, "class" to clazz)
                }
            }
        }
        return mapper.writeValueAsString(all)
    }
}
