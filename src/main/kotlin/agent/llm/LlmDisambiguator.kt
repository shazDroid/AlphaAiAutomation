package agent.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ui.OllamaClient

interface DialogAdvisor {
    fun decide(userGoal: String?, dialog: DialogSnapshot): DialogDecision?
}

data class DialogSnapshot(
    val rootXPath: String,
    val title: String?,
    val message: String?,
    val buttons: List<DialogButton>
)

data class DialogButton(val text: String, val xpath: String)

sealed class DialogDecision {
    data class Tap(val buttonText: String) : DialogDecision()
    data class AskUser(val reason: String) : DialogDecision()
    object Retry : DialogDecision()
    object Dismiss : DialogDecision()
    object Ignore : DialogDecision()
}


data class LlmUiCandidate(
    val id: String,
    val label: String,          // visible label we’ll show the LLM
    val role: String,           // e.g. "bottom_nav", "tab", "button", "chip", "list_item", "dialog_button", "other"
    val clickable: Boolean,     // surface hint for LLM
    val containerId: String?,   // nav/tab container id if any
)

data class LlmScreenContext(
    val screenTitle: String? = null,   // optional, if you detect it
    val activity: String? = null,      // driver.currentActivity if you want
)

class LlmDisambiguator(
    private val ollama: OllamaClient
) {
    private val mapper = jacksonObjectMapper()

    /**
     * Ask the LLM to choose one candidate id.
     * If anything goes wrong, return null (caller will fall back deterministically).
     */
    fun decide(hint: String, candidates: List<LlmUiCandidate>, ctx: LlmScreenContext = LlmScreenContext()): String? {
        if (candidates.isEmpty()) return null

        // Keep payload tiny & deterministic
        val payload = mapper.writeValueAsString(
            mapOf(
                "hint" to hint,
                "screen" to mapOf("title" to ctx.screenTitle, "activity" to ctx.activity),
                "candidates" to candidates.map {
                    mapOf(
                        "id" to it.id,
                        "label" to it.label,
                        "role" to it.role,
                        "clickable" to it.clickable,
                        "containerId" to it.containerId
                    )
                }
            )
        )

        val system = """
            You are selecting UI elements for Android automation.
            You get a user hint and a small list of candidates on the current screen.
            PICK EXACTLY ONE winner id.
            Rules:
            - Prefer items whose label best matches the hint semantics, not only tokens.
            - If hint sounds like "tab", "menu", "home", "accounts", "cards", "transfer & pay":
                prefer role 'bottom_nav' or 'tab' if present.
            - Prefer items that are clickable=true when multiple labels match.
            - If two candidates tie semantically, prefer the one inside a nav/tab container (containerId != null).
            - If still tied, choose the one with the MOST complete label match (all words, same order).
            Output JSON only: {"winnerId":"<id>"}
            No extra keys, no prose.
        """.trimIndent()

        val user = buildString {
            appendLine("CONTEXT_JSON_START")
            appendLine(payload)
            appendLine("CONTEXT_JSON_END")
            appendLine("Return only JSON as specified.")
        }

        return try {
            val raw = OllamaClient.completeRawBlocking(system = system, user = user, temperature = 0.0)
            val node = mapper.readTree(raw.trim())
            node.get("winnerId")?.asText()?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }
}
