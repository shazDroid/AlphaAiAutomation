package agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ui.OllamaClient

class IntentParser {
    private val mapper = jacksonObjectMapper().registerKotlinModule()

    private val systemPrompt = """
        Return ONLY a single minified JSON object for ActionPlan:
        {"title": string, "steps":[{"index":int,"type":"LAUNCH_APP|TAP|INPUT_TEXT|SCROLL_TO|WAIT_TEXT|ASSERT_TEXT|BACK|SLEEP","targetHint":string?,"value":string?}]}
        No prose. No code fences. No extra keys.
    """.trimIndent()

    fun parse(nlTask: String): ActionPlan {
        // Try strict JSON mode first
        val first = OllamaClient.completeJsonBlocking(systemPrompt, nlTask).trim()
        val firstJson = extractJson(first)
        runCatching { return mapper.readValue<ActionPlan>(firstJson) }

        // If it still fails, reprompt with a stronger hint and parse again
        val secondUser = """
            Task: $nlTask
            Output: ONLY ActionPlan JSON (minified). Start with { and end with }.
        """.trimIndent()
        val second = OllamaClient.completeJsonBlocking(systemPrompt, secondUser).trim()
        val secondJson = extractJson(second)
        runCatching { return mapper.readValue<ActionPlan>(secondJson) }

        return ActionPlan(
            title = "Parsed Task",
            steps = listOf(
                PlanStep(index = 1, type = StepType.LAUNCH_APP, targetHint = "com.example.app")
            )
        )
    }

    private fun extractJson(s: String): String {
        // code fence first
        val fenced = Regex("```(?:json)?\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)
            .find(s)?.groupValues?.get(1)
        if (fenced != null) return fenced.trim()
        // largest curly block
        val i = s.indexOf('{'); val j = s.lastIndexOf('}')
        if (i != -1 && j > i) return s.substring(i, j + 1).trim()
        return s
    }
}
