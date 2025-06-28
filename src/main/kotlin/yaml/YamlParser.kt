package yaml

import org.yaml.snakeyaml.Yaml
import java.io.File

class YamlParser {
    fun parse(filePath: String): Map<String, Any> {
        val yaml = Yaml()
        val inputStream = File(filePath).inputStream()
        return yaml.load(inputStream)
    }
}
