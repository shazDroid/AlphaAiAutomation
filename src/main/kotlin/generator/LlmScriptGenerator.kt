package generator

import agent.ActionPlan
import agent.Snapshot
import agent.StepType
import agent.Strategy
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ui.OllamaClient
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class LlmScriptGenerator(
    private val ollama: OllamaClient,
    private val outDir: File
) {
    private val mapper = jacksonObjectMapper()

    fun generate(
        plan: ActionPlan,
        timeline: List<Snapshot>,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): File {
        outDir.mkdirs()

        val total = 5
        var step = 0
        fun tick(msg: String) { step += 1; onProgress(step, total, msg) }

        // ------ SUMMARY_JSON ------------------------------------------------------
        tick("Preparing context")
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

        // ------ Build action-specific selector maps from successful interactions --
        data class Rec(val step: Int, val hint: String, val type: StepType, val sel: String)

        fun toSelectorString(strategy: Strategy, value: String): String? = when (strategy) {
            Strategy.XPATH       -> value                      // //...
            Strategy.ID          -> "id=$value"               // id=com.pkg:id/foo
            Strategy.DESC        -> "~$value"                 // ~content-desc
            Strategy.UIAUTOMATOR -> "android=$value"          // android=new UiSelector()...
            else                 -> null                      // ignore unknowns
        }

        val recs: List<Rec> = timeline.mapNotNull { snap ->
            val loc = snap.resolvedLocator ?: return@mapNotNull null
            val hint = (snap.targetHint ?: "").trim()
            if (hint.isEmpty()) return@mapNotNull null
            val stepType = stepTypeForIndex(plan, snap.stepIndex) ?: return@mapNotNull null
            val sel = toSelectorString(loc.strategy, loc.value) ?: return@mapNotNull null
            if (sel.isBlank()) return@mapNotNull null
            Rec(step = snap.stepIndex, hint = hint, type = stepType, sel = sel)
        }

        fun lastByType(targetTypes: Set<StepType>): Map<String, String> =
            recs.filter { it.type in targetTypes }
                .groupBy { it.hint }
                .mapValues { (_, g) -> g.maxBy { it.step }.sel }

        val inputSelectors   = lastByType(setOf(StepType.INPUT_TEXT))
        val tapSelectors     = lastByType(setOf(StepType.TAP))
        val assertSelectors  = lastByType(setOf(StepType.WAIT_TEXT, StepType.CHECK, StepType.ASSERT_TEXT))
        val lockedSelectors  = recs.groupBy { it.hint }.mapValues { (_, g) -> g.maxBy { it.step }.sel }

        // All hints referenced by steps must have some recorded selector
        val requiredHints = plan.steps
            .filter { it.type in setOf(StepType.INPUT_TEXT, StepType.TAP, StepType.WAIT_TEXT, StepType.CHECK, StepType.ASSERT_TEXT) }
            .mapNotNull { it.targetHint?.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val missing = buildList<String> {
            for (h in requiredHints) {
                val hasAny = inputSelectors.containsKey(h) ||
                        tapSelectors.containsKey(h) ||
                        assertSelectors.containsKey(h) ||
                        lockedSelectors.containsKey(h)
                if (!hasAny) add(h)
            }
        }
        require(missing.isEmpty()) {
            "Missing recorded selector for: $missing. Ensure the agent stores the selector actually used for those interactions."
        }

        // Serialize maps for LLM context
        val inputJson   = mapper.writeValueAsString(mapOf("bestByHint" to inputSelectors))
        val tapJson     = mapper.writeValueAsString(mapOf("bestByHint" to tapSelectors))
        val assertJson  = mapper.writeValueAsString(mapOf("bestByHint" to assertSelectors))
        val lockedJson  = mapper.writeValueAsString(mapOf("bestByHint" to lockedSelectors))
        val rolesJson   = mapper.writeValueAsString(
            mapOf(
                "inputHints"  to plan.steps.filter { it.type == StepType.INPUT_TEXT }.mapNotNull { it.targetHint },
                "tapHints"    to plan.steps.filter { it.type == StepType.TAP }.mapNotNull { it.targetHint },
                "assertHints" to plan.steps.filter { it.type in setOf(StepType.WAIT_TEXT, StepType.CHECK, StepType.ASSERT_TEXT) }.mapNotNull { it.targetHint }
            )
        )

        // ------ System instruction ----------
        val systemRules = """
            You are a code generator for a TypeScript + WebdriverIO + Cucumber + Appium (Android) repository.

            OUTPUT CONTRACT — print EXACTLY these four sections, nothing else:
            ### BASE_PAGE_START ... ### BASE_PAGE_END
            ### ANDROID_PAGE_START ... ### ANDROID_PAGE_END
            ### STEP_DEFS_START ... ### STEP_DEFS_END
            ### FEATURE_FILE_START ... ### FEATURE_FILE_END

            SELECTOR SOURCES (authoritative):
            - INPUT_SELECTORS.bestByHint: exact selector strings used for INPUT_TEXT (typing).
            - TAP_SELECTORS.bestByHint:   exact selector strings used for taps.
            - ASSERT_SELECTORS.bestByHint: exact selector strings used for visibility/assert waits.
            - LOCKED_SELECTORS.bestByHint: last successful selector for any action (fallback only if the role-specific map lacks the hint).

            CRITICAL RULES:
            - Use the selector string **verbatim**. Do NOT transform or invent.
            - Page objects must embed selectors directly in WDIO `$()`:
                * If selector starts with "//"     -> return $('//…')      // XPath
                * If selector starts with "id="    -> return $('id=…')      // resource-id
                * If selector starts with "android=" -> return $('android=…') // UiAutomator
                * If selector starts with "~"      -> return $('~…')        // content-desc
            - Do NOT use UiSelectorBuilderAndroid or any builder/regex helper.
            - For each getter:
                * If its hint is in inputHints, use INPUT_SELECTORS.bestByHint[hint]
                * If in tapHints, use TAP_SELECTORS.bestByHint[hint]
                * If in assertHints, use ASSERT_SELECTORS.bestByHint[hint]
                * Otherwise fallback to LOCKED_SELECTORS.bestByHint[hint]

            FILE STYLE (match existing project):
            1) pageobjects/base/BaseDashboardPage.ts
               - TypeScript; export default abstract class BaseDashboardPage
               - Declare getters/methods actually used by the scenario (txtUsername, txtPassword, btnLogin, tabServices, banner, etc).

            2) pageobjects/android/AndroidDashboardPage.ts
               - TypeScript; class AndroidDashboardPage extends BaseDashboardPage
               - Each getter returns $('<selector>') with the exact selector from the correct map (see above).

            3) step-definitions/dashboard/dashboard.steps.ts
               - WDIO Cucumber with async/await; use only page-object getters/methods.

            4) features/services/<slug>.feature
               - Gherkin; Scenario Outline & Examples.
               e.g Feature: Action Plan

                    Scenario: Action Plan
                    Given I login with <"username"> and <"password">
                    | username | password | 
                    | kycuser2 | Test@112 |

            Begin your reply with: ### BASE_PAGE_START
            No prose/explanations outside the four sections.
        """.trimIndent()

        // ------ Compose LLM user prompt with context ------------------------------
        tick("Calling LLM")
        val userPrompt = buildString {
            appendLine("CONTEXT_JSON_START")
            appendLine("SUMMARY_JSON:"); appendLine(summaryJson)
            appendLine()
            appendLine("INPUT_SELECTORS:"); appendLine(inputJson)
            appendLine("TAP_SELECTORS:"); appendLine(tapJson)
            appendLine("ASSERT_SELECTORS:"); appendLine(assertJson)
            appendLine("LOCKED_SELECTORS:"); appendLine(lockedJson)
            appendLine("HINT_ROLES:"); appendLine(rolesJson)
            appendLine("CONTEXT_JSON_END")
            appendLine()
            appendLine("Now output the four sections exactly as specified above.")
            appendLine()
            appendLine("### BASE_PAGE_START")
            appendLine("```ts")
            appendLine("// pageobjects/base/BaseDashboardPage.ts")
            appendLine("// Abstract class with getters/methods used by the steps")
            appendLine("```")
            appendLine("### BASE_PAGE_END")
            appendLine()
            appendLine("### ANDROID_PAGE_START")
            appendLine("```ts")
            appendLine("// pageobjects/android/AndroidDashboardPage.ts")
            appendLine("// Use INPUT_SELECTORS for INPUT_TEXT; TAP_SELECTORS for taps; ASSERT_SELECTORS for waits; else LOCKED_SELECTORS.")
            appendLine("// Embed selector strings verbatim via $('id=...') | $('//...') | $('android=...') | $('~...'). No builders.")
            appendLine("```")
            appendLine("### ANDROID_PAGE_END")
            appendLine()
            appendLine("### STEP_DEFS_START")
            appendLine("```ts")
            appendLine("// step-definitions/dashboard/dashboard.steps.ts")
            appendLine("// Use ONLY page object getters/methods")
            appendLine("```")
            appendLine("### STEP_DEFS_END")
            appendLine()
            appendLine("### FEATURE_FILE_START")
            appendLine("```gherkin")
            appendLine("// features/services/<slug>.feature (slug from the title)")
            appendLine("```")
            appendLine("### FEATURE_FILE_END")
        }

        // ------  Debug -------------------------------------------------------------
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val debugDir = outDir.resolve("_debug_$stamp").apply { mkdirs() }
        debugWrite(debugDir, "summary.json", summaryJson)
        debugWrite(debugDir, "input_selectors.json", inputJson)
        debugWrite(debugDir, "tap_selectors.json", tapJson)
        debugWrite(debugDir, "assert_selectors.json", assertJson)
        debugWrite(debugDir, "locked_selectors.json", lockedJson)
        debugWrite(debugDir, "roles.json", rolesJson)
        debugWrite(debugDir, "prompt_markers.txt", userPrompt)

        // ------  LLM call ----------------------------------------------------------
        var raw = OllamaClient.completeRawBlocking(system = systemRules, user = userPrompt, temperature = 0.0)
        raw = normalizeMarkers(raw)
        debugWrite(debugDir, "raw_response_1.txt", raw)

        // One repair attempt if any section missing
        fun extract(name: String, end: String) = extractMarked(raw, name, end)
        var baseBlock    = extract("BASE_PAGE_START", "BASE_PAGE_END")
        var androidBlock = extract("ANDROID_PAGE_START", "ANDROID_PAGE_END")
        var stepsBlock   = extract("STEP_DEFS_START", "STEP_DEFS_END")
        var featureBlock = extract("FEATURE_FILE_START", "FEATURE_FILE_END")

        if (listOf(baseBlock, androidBlock, stepsBlock, featureBlock).any { it.isNullOrBlank() }) {
            val repair = """
                Your previous reply missed required sections.
                Reprint all four sections exactly, starting with: ### BASE_PAGE_START
            """.trimIndent()
            raw = OllamaClient.completeRawBlocking(system = systemRules, user = repair + "\n\n" + userPrompt, temperature = 0.0)
            raw = normalizeMarkers(raw)
            debugWrite(debugDir, "raw_response_2_repair.txt", raw)
            baseBlock    = extract("BASE_PAGE_START", "BASE_PAGE_END")
            androidBlock = extract("ANDROID_PAGE_START", "ANDROID_PAGE_END")
            stepsBlock   = extract("STEP_DEFS_START", "STEP_DEFS_END")
            featureBlock = extract("FEATURE_FILE_START", "FEATURE_FILE_END")
        }

        require(!baseBlock.isNullOrBlank())    { "LLM failed to produce BaseDashboardPage" }
        require(!androidBlock.isNullOrBlank()) { "LLM failed to produce AndroidDashboardPage" }
        require(!stepsBlock.isNullOrBlank())   { "LLM failed to produce step definitions" }
        require(!featureBlock.isNullOrBlank()) { "LLM failed to produce feature file" }

        // ------ Validate: no builder, no prose, selectors embedded via $() --------
        val androidCode = stripFences(androidBlock!!)
        require(!Regex("""UiSelectorBuilderAndroid""").containsMatchIn(androidCode)) {
            "Generated page must not use UiSelectorBuilderAndroid."
        }

        require(Regex("""\$\(['"]""").containsMatchIn(androidCode)) {
            "Generated page did not embed any WDIO $('…') selectors."
        }

        // ------ Write files -------------------------------------------------------
        tick("Writing files")

        val baseCode    = stripFences(baseBlock!!)
        val stepsCode   = stripFences(stepsBlock!!)
        val featureCode = stripFences(featureBlock!!)

        write(outDir.resolve("pageobjects/base"), "BaseDashboardPage.ts", baseCode)
        write(outDir.resolve("pageobjects/android"), "AndroidDashboardPage.ts", androidCode)
        write(outDir.resolve("step-definitions/dashboard"), "dashboard.steps.ts", stepsCode)

        val slug = slugify(plan.title.ifBlank { "generated-scenario" })
        write(outDir.resolve("features/services"), "$slug.feature", featureCode)

        tick("Done")
        println("✅ [Gen] Scripts written to ${outDir.absolutePath}")
        debugWrite(debugDir, "done.txt", "OK")
        return outDir
    }

    // ------------------------------- Helpers ---------------------------------------

    private fun slugify(s: String): String =
        s.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun write(dir: File, name: String, content: String) {
        dir.mkdirs()
        File(dir, name).writeText(content)
    }

    private fun debugWrite(folder: File, name: String, data: String) {
        try { File(folder, name).writeText(data) } catch (_: Throwable) {}
    }

    private fun normalizeMarkers(src: String): String {
        var s = src.replace("\r\n", "\n")
        s = s.replace(Regex("""\n##\s+BASE_PAGE_START""", RegexOption.IGNORE_CASE), "\n### BASE_PAGE_START")
            .replace(Regex("""\n##\s+BASE_PAGE_END""", RegexOption.IGNORE_CASE), "\n### BASE_PAGE_END")
            .replace(Regex("""\n##\s+ANDROID_PAGE_START""", RegexOption.IGNORE_CASE), "\n### ANDROID_PAGE_START")
            .replace(Regex("""\n##\s+ANDROID_PAGE_END""", RegexOption.IGNORE_CASE), "\n### ANDROID_PAGE_END")
            .replace(Regex("""\n##\s+STEP_DEFS_START""", RegexOption.IGNORE_CASE), "\n### STEP_DEFS_START")
            .replace(Regex("""\n##\s+STEP_DEFS_END""", RegexOption.IGNORE_CASE), "\n### STEP_DEFS_END")
            .replace(Regex("""\n##\s+FEATURE_FILE_START""", RegexOption.IGNORE_CASE), "\n### FEATURE_FILE_START")
            .replace(Regex("""\n##\s+FEATURE_FILE_END""", RegexOption.IGNORE_CASE), "\n### FEATURE_FILE_END")
        return s
    }

    private fun extractMarked(src: String, start: String, end: String): String? {
        val re = Regex("""(?is)###\s+$start\b.*?\R+([\s\S]*?)\R*###\s+$end\b""")
        return re.find(src)?.groupValues?.get(1)?.trim()
    }

    private fun stripFences(code: String): String {
        val fence = Regex("""^\s*```[\w-]*\s*\n([\s\S]*?)\n?```\s*$""", RegexOption.DOT_MATCHES_ALL)
        val m = fence.find(code)
        return (m?.groupValues?.get(1) ?: code).trim()
    }

    private fun stepTypeForIndex(plan: ActionPlan, idx: Int): StepType? =
        plan.steps.firstOrNull { it.index == idx }?.type
}
