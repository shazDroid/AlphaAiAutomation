package yaml

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.*

object YamlFlowLoader {
    private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    fun loadFlowFromString(yamlContent: String): TestFlow {
        return mapper.readValue(yamlContent, TestFlow::class.java)
    }
}
