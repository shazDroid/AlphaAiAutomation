package agent.llm

import org.json.JSONObject
import ui.OllamaClient
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Pluggable LLM disambiguator backed by Google Gemini.
 * Matches your interface: fun decide(instruction, candidates, context): String?
 *
 * Env:
 *   GEMINI_API_KEY (required)
 *   GEMINI_MODEL   (optional, default: gemini-1.5-flash)
 */
class GeminiDisambiguator(
    private val apiKey: String = System.getenv("GEMINI_API_KEY")
        ?: throw IllegalStateException("GEMINI_API_KEY env var not set"),
    private val model: String = System.getenv("GEMINI_MODEL") ?: "gemini-1.5-flash"
) : LlmDisambiguator(OllamaClient) {

    // **** NOTE: context is NON-null to match your interface ****
    override fun decide(
        instruction: String,
        candidates: List<LlmUiCandidate>,
        context: LlmScreenContext
    ): String? {
        val shortlist = candidates.take(14)

        val candJson = shortlist.joinToString(",") { c ->
            val label = (c.label ?: "").replace("\n", " ").take(120)
            val role = c.role ?: ""
            """{"id":"${esc(c.id)}","label":"${esc(label)}","role":"${esc(role)}","clickable":${c.clickable}}"""
        }

        val sys = """
Select exactly ONE UI element for a tap/toggle. Rules:
- Prefer exact phrase matches over partial matches (e.g., "Transfer & Pay" beats "Pay").
- Prefer clickable=true when ties exist.
- Return ONLY JSON: {"candidateId":"<id>","reason":"<why>"}.
""".trimIndent()

        val user = """
INSTRUCTION: ${instruction.trim()}
CANDIDATES: [$candJson]
ACTIVITY: ${context.activity ?: "unknown"}
""".trimIndent()

        val requestBody = """
{
  "contents": [{
    "parts": [
      {"text": "${esc(sys)}"},
      {"text": "${esc(user)}"}
    ]
  }],
  "generationConfig": {"temperature": 0.2}
}
""".trimIndent()

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connectTimeout = 12000
            readTimeout = 12000
        }

        conn.outputStream.use { it.write(requestBody.toByteArray(StandardCharsets.UTF_8)) }

        val resp = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
            .bufferedReader().use { it.readText() }

        val text = try {
            JSONObject(resp)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
                .getJSONObject(0).getString("text")
        } catch (_: Exception) {
            """{"candidateId": null}"""
        }

        val json = extractJson(text)
        return json.optString("candidateId", null).takeIf { !it.isNullOrBlank() }
    }

    private fun esc(s: String?): String = (s ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    private fun extractJson(s: String): JSONObject {
        val m = Regex("(?s)\\{.*?\\}").findAll(s).toList().lastOrNull()
        return try {
            JSONObject(m?.value ?: s)
        } catch (_: Exception) {
            JSONObject("""{"candidateId": null}""")
        }
    }
}
