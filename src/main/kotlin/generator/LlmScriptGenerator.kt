package generator

import agent.ActionPlan
import agent.Locator
import agent.Snapshot
import agent.StepType
import agent.Strategy
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ui.OllamaClient
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class LlmScriptGenerator(
    private val ollama: OllamaClient,
    private val outDir: File
) {
    private val mapper = jacksonObjectMapper()

    fun generate(plan: ActionPlan, timeline: List<Snapshot>) {
        outDir.mkdirs()

        // ---- SUMMARY_JSON ----------------------------------------------------------
        val summaryJson = mapper.writeValueAsString(
            mapOf(
                "title" to plan.title,
                "steps" to plan.steps.map { s ->
                    mapOf(
                        "index" to s.index,
                        "type" to s.type.name,
                        "targetHint" to s.targetHint,
                        "value" to s.value
                    )
                }
            )
        )

        // ---- LOCATORS_JSON.bestByHint from snapshots ----------------------------------------
        data class LRec(
            val step: Int,
            val hint: String,
            val strategy: String,
            val value: String,
            val wdio: String
        )

        val recs: List<LRec> = timeline.mapNotNull { snap ->
            val loc: Locator = snap.resolvedLocator ?: return@mapNotNull null
            val hint = (snap.targetHint ?: "").trim()
            if (hint.isEmpty()) return@mapNotNull null
            LRec(
                step = snap.stepIndex,
                hint = hint,
                strategy = loc.strategy.name,
                value = loc.value,
                wdio = toWdioSelector(loc.strategy, loc.value)
            )
        }

        fun stratRank(s: String) = when (s) {
            "ID" -> 4; "DESC" -> 3; "UIAUTOMATOR" -> 2; "XPATH" -> 1; else -> 1
        }

        val bestFromRecs: Map<String, String> =
            recs.groupBy { it.hint }
                .mapValues { (_, group) ->
                    group.maxWith(compareBy<LRec> { stratRank(it.strategy) }.thenBy { it.step }).wdio
                }

        // Ensure every referenced hint has a selector
        val mergedBestByHint = linkedMapOf<String, String>().apply { putAll(bestFromRecs) }
        val referencedHints = plan.steps
            .filter { it.type in setOf(StepType.INPUT_TEXT, StepType.TAP, StepType.WAIT_TEXT) }
            .mapNotNull { it.targetHint?.trim() }
            .filter { it.isNotEmpty() }
        for (hint in referencedHints) {
            if (!mergedBestByHint.containsKey(hint)) {
                mergedBestByHint[hint] = fallbackSelectorFor(hint)
            }
        }

        val locatorsJson = mapper.writeValueAsString(
            mapOf(
                "elements" to recs,
                "bestByHint" to mergedBestByHint
            )
        )

        val selectorsJson = mapper.writeValueAsString(
            mapOf("bestByHint" to mergedBestByHint) // ← no giant elements[] list
        )

        // ---- Prompt with strict markers ------------------------------------------------------
        val userPrompt = buildString {
            appendLine("You are a senior test automation generator for WebdriverIO + Cucumber + Appium (Android).")
            appendLine("Do NOT output JSON. Do NOT echo the context. Output ONLY the four sections,")
            appendLine("each wrapped by its exact start/end markers. Begin your reply with the line:")
            appendLine("### BASE_CLASS_START")
            appendLine()
            appendLine("CONTEXT_JSON_START")
            appendLine("SUMMARY_JSON:"); appendLine(summaryJson)
            appendLine()
            appendLine("SELECTORS:"); appendLine(selectorsJson)
            appendLine("CONTEXT_JSON_END")
            appendLine()
            appendLine("RULES:")
            appendLine("- Never output an ActionPlan or any object with keys like \"title\" or \"steps\".")
            appendLine("- Use SELECTORS.bestByHint EXACTLY for PlatformPage getters. No invented selectors.")
            appendLine("- Use TypeScript code fences inside each section. Nothing outside markers.")
            appendLine()
            appendLine("### BASE_CLASS_START")
            appendLine("TypeScript: abstract class `BasePage` with ONLY abstract getters for ALL hints present in SELECTORS.bestByHint.")
            appendLine("Each getter signature:  public abstract get <camelName>(): ChainablePromiseElement<WebdriverIO.Element>;")
            appendLine("```ts")
            appendLine("export default abstract class BasePage {")
            appendLine("  // example shape (real list must cover ALL hints)")
            appendLine("  public abstract get login(): ChainablePromiseElement<WebdriverIO.Element>;")
            appendLine("}")
            appendLine("```")
            appendLine("### BASE_CLASS_END")
            appendLine()
            appendLine("### PLATFORM_CLASS_START")
            appendLine("TypeScript: class `PlatformPage` extends `BasePage`. Override EVERY getter using EXACT strings from SELECTORS.bestByHint.")
            appendLine("Example form:  return $('id=com.example:id/loginBtn')  OR  return $('android=new UiSelector().text(\"Login\")');")
            appendLine("```ts")
            appendLine("import BasePage from './BasePage';")
            appendLine("class PlatformPage extends BasePage { /* getters filled with SELECTORS.bestByHint */ }")
            appendLine("export default new PlatformPage();")
            appendLine("```")
            appendLine("### PLATFORM_CLASS_END")
            appendLine()
            appendLine("### STEP_DEFS_START")
            appendLine("TypeScript step defs using ONLY PlatformPage getters (no raw selectors):")
            appendLine("- Given the app is launched")
            appendLine("- When I type \"{value}\" into \"{hint}\"")
            appendLine("- When I tap \"{hint}\"")
            appendLine("- Then I should see \"{hint}\" (skip if empty)")
            appendLine("Include a tiny helper that maps hints to the camelCase getter key.")
            appendLine("```ts")
            appendLine("// step defs here")
            appendLine("```")
            appendLine("### STEP_DEFS_END")
            appendLine()
            appendLine("### FEATURE_FILE_START")
            appendLine("One concise .feature with Feature/Scenario following SUMMARY_JSON order.")
            appendLine("```gherkin")
            appendLine("// gherkin here")
            appendLine("```")
            appendLine("### FEATURE_FILE_END")
        }


        // ---- Debug folder --------------------------------------------------------------------
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val debugDir = outDir.resolve("_debug_$stamp").apply { mkdirs() }
        debugWrite(debugDir, "summary.json", summaryJson)
        debugWrite(debugDir, "locators.json", locatorsJson)
        debugWrite(debugDir, "prompt_markers.txt", userPrompt)

        // ---- LLM call (strict markers) -------------------------------------------------------
        var raw = ollama.completeJsonBlocking(system = "", user = userPrompt)
        debugWrite(debugDir, "raw_response_1.txt", raw)

        var baseBlock = extractMarked(raw, "BASE_CLASS_START", "BASE_CLASS_END")
        var platformBlock = extractMarked(raw, "PLATFORM_CLASS_START", "PLATFORM_CLASS_END")
        var stepsBlock = extractMarked(raw, "STEP_DEFS_START", "STEP_DEFS_END")
        var featureBlock = extractMarked(raw, "FEATURE_FILE_START", "FEATURE_FILE_END")

        // One repair attempt (LLM only) if any section missing
        if (listOf(baseBlock, platformBlock, stepsBlock, featureBlock).any { it.isNullOrBlank() }) {
            val repairPrompt = buildString {
                appendLine("Your previous reply violated the format. Do NOT output JSON.")
                appendLine("Start NOW with the line: ### BASE_CLASS_START")
                appendLine("Then emit the four sections exactly as instructed. Nothing else.")
                appendLine()
                appendLine("CONTEXT_JSON_START")
                appendLine("SUMMARY_JSON:"); appendLine(summaryJson)
                appendLine()
                appendLine("SELECTORS:"); appendLine(selectorsJson)
                appendLine("CONTEXT_JSON_END")
                appendLine()
                appendLine("Sections to output verbatim in this order (each with fenced code inside):")
                appendLine("### BASE_CLASS_START ... ### BASE_CLASS_END")
                appendLine("### PLATFORM_CLASS_START ... ### PLATFORM_CLASS_END")
                appendLine("### STEP_DEFS_START ... ### STEP_DEFS_END")
                appendLine("### FEATURE_FILE_START ... ### FEATURE_FILE_END")
            }

            raw = ollama.completeJsonBlocking(system = "", user = repairPrompt)
            debugWrite(debugDir, "raw_response_2_repair.txt", raw)

            baseBlock = extractMarked(raw, "BASE_CLASS_START", "BASE_CLASS_END")
            platformBlock = extractMarked(raw, "PLATFORM_CLASS_START", "PLATFORM_CLASS_END")
            stepsBlock = extractMarked(raw, "STEP_DEFS_START", "STEP_DEFS_END")
            featureBlock = extractMarked(raw, "FEATURE_FILE_START", "FEATURE_FILE_END")
        }

        require(!baseBlock.isNullOrBlank())     { "LLM failed to produce BASE_CLASS section" }
        require(!platformBlock.isNullOrBlank()) { "LLM failed to produce PLATFORM_CLASS section" }
        require(!stepsBlock.isNullOrBlank())    { "LLM failed to produce STEP_DEFS section" }
        require(!featureBlock.isNullOrBlank())  { "LLM failed to produce FEATURE_FILE section" }

        val files = mapOf(
            "BasePage.ts" to stripFences(baseBlock!!),
            "PlatformPage.ts" to stripFences(platformBlock!!),
            "StepDefinitions.ts" to stripFences(stepsBlock!!),
            "feature.feature" to stripFences(featureBlock!!)
        )
        debugWrite(debugDir, "parsed_from_markers.json", mapper.writeValueAsString(files))

        write(outDir.resolve("pages"), "BasePage.ts", files["BasePage.ts"])
        write(outDir.resolve("pages"), "PlatformPage.ts", files["PlatformPage.ts"])
        write(outDir.resolve("steps"), "StepDefinitions.ts", files["StepDefinitions.ts"])
        write(outDir.resolve("features"), "feature.feature", files["feature.feature"])

        println("✅ [Gen] Scripts written to ${outDir.absolutePath}")
        debugWrite(debugDir, "done.txt", "OK")
    }

    // --------------------------- Helpers ------------------------------------

    private fun toWdioSelector(strategy: Strategy, value: String): String = when (strategy) {
        Strategy.ID          -> "id=$value"
        Strategy.DESC        -> "~$value"
        Strategy.UIAUTOMATOR -> "android=$value"
        Strategy.XPATH       -> value
        else                 -> value
    }

    private fun fallbackSelectorFor(hint: String): String {
        val clean = normalizeLabel(hint)
        return """android=new UiSelector().textContains("$clean")"""
    }

    private fun normalizeLabel(raw: String): String {
        var s = raw
            .replace(Regex("(?i)\\b(click|tap|press|open|go to|select|choose|wait|assert)\\b"), "")
            .replace(Regex("(?i)\\b(button|tab|icon|item|menu|link|option|text|field|input)\\b"), "")
            .replace(Regex("[\\p{Punct}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (s.isBlank()) s = raw.trim()
        return if (s == s.uppercase()) s else s.replaceFirstChar { it.uppercase() }
    }

    private fun write(dir: File, name: String, content: String?) {
        require(!content.isNullOrBlank()) { "Missing content for $name" }
        dir.mkdirs()
        File(dir, name).writeText(content!!)
    }

    private fun debugWrite(folder: File, name: String, data: String) {
        try { File(folder, name).writeText(data) } catch (_: Throwable) {}
    }

    private fun extractMarked(src: String, start: String, end: String): String? {
        val re = Regex("###\\s+$start\\s*\\n(.*?)\\n###\\s+$end", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        return re.find(src)?.groupValues?.get(1)?.trim()
    }

    private fun stripFences(code: String): String {
        val m = Regex("^```[a-zA-Z]*\\s*\\n([\\s\\S]*?)\\n```\\s*$", RegexOption.MULTILINE)
        val mm = m.find(code)
        return (mm?.groupValues?.get(1) ?: code).trim()
    }
}
