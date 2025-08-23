package generator

import agent.ActionPlan
import agent.Snapshot
import agent.Locator
import agent.Strategy
import agent.StepType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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

        // Build locator records from snapshots (exact selectors actually used)
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

        fun stratRank(s: String) = when (s) { "ID" -> 4; "DESC" -> 3; "UIAUTOMATOR" -> 2; "XPATH" -> 1; else -> 1 }
        val bestFromRecs: Map<String, String> =
            recs.groupBy { it.hint }
                .mapValues { (_, group) ->
                    group.maxWith(
                        compareBy<LRec> { stratRank(it.strategy) }.thenBy { it.step }
                    ).wdio
                }

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

        // ---- Prompt (JSON) ----------------------------------------
        val system = """
            You are a senior test automation generator.
            Return a SINGLE JSON object with EXACTLY these 4 keys and STRING values:
            {
              "BasePage.ts": "...",
              "PlatformPage.ts": "...",
              "StepDefinitions.ts": "...",
              "feature.feature": "..."
            }
            No markdown. No backticks. No extra keys.
            Target stack: WebdriverIO + Cucumber + Appium (Android).
            RULES:
            - Use the provided selectors in LOCATORS_JSON.bestByHint EXACTLY as given when implementing getters.
              Do not invent different selectors.
              WebdriverIO examples:
                $('id=com.example:id/login')               // resource-id
                $('~Login')                                // content-desc (accessibility id)
                $('android=new UiSelector().textContains("Login")')
                $('//hierarchy/...')                       // XPath
            - BasePage.ts: abstract getters only (no inline selectors).
            - PlatformPage.ts: extends BasePage and implements the getters using those exact selectors.
            - StepDefinitions.ts: use getters only (no raw selectors).
            - feature.feature: one concise scenario based on SUMMARY_JSON.title and steps.
        """.trimIndent()

        val user = buildString {
            appendLine("SUMMARY_JSON:")
            appendLine(summaryJson)
            appendLine()
            appendLine("LOCATORS_JSON:")
            appendLine(locatorsJson)
            appendLine()
            appendLine("Produce ONLY the 4-key JSON object described above.")
        }

        // ---- Debug folder ---------------------------------------------------
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val debugDir = outDir.resolve("_debug_$stamp").apply { mkdirs() }
        debugWrite(debugDir, "summary.json", summaryJson)
        debugWrite(debugDir, "locators.json", locatorsJson)
        debugWrite(debugDir, "prompt.txt", user)
        println("🟦 [Gen] Debug folder: ${debugDir.absolutePath}")

        // ---- LLM call -------------------------------------------------------
        val raw = ollama.completeJsonBlocking(system = system, user = user)
        debugWrite(debugDir, "raw_response.txt", raw)

        val jsonBlob = extractJsonBlock(raw)
        debugWrite(debugDir, "json_blob.txt", jsonBlob)

        var files: Map<String, String>? = parseOrCoerceToFileMap(jsonBlob)

        fun looksBad(s: String?): Boolean {
            if (s.isNullOrBlank()) return true
            val t = s.trim()
            if (t.length < 80) return true
            val badPhrases = listOf("abstract getters only", "use getters only", "one concise scenario")
            return badPhrases.any { t.contains(it, ignoreCase = true) }
        }

        if (files == null || files.values.any { looksBad(it) }) {
            println("🟨 [Gen] First attempt invalid. Trying one-shot repair…")
            val repaired = retryFormatToFileMap(ollama, """
                SUMMARY_JSON:
                $summaryJson

                LOCATORS_JSON:
                $locatorsJson

                The 4 string fields must contain actual TypeScript/feature code.
            """.trimIndent())
            debugWrite(debugDir, "json_blob_fixed.txt", mapper.writeValueAsString(repaired))
            files = repaired
        }

        if (files == null || files.values.any { looksBad(it) }) {
            println("🟧 [Gen] Model still returned placeholders. Falling back to deterministic code.")
            files = renderDeterministicFiles(plan, mergedBestByHint)
            debugWrite(debugDir, "fallback_rendered.json", mapper.writeValueAsString(files))
        }

        println("🟦 [Gen] Final keys: ${files.keys}")
        debugWrite(debugDir, "json_parsed_keys.txt", files.keys.joinToString("\n"))

        require(!files["BasePage.ts"].isNullOrBlank()) { "LLM missing BasePage.ts" }
        require(!files["PlatformPage.ts"].isNullOrBlank()) { "LLM missing PlatformPage.ts" }
        require(!files["StepDefinitions.ts"].isNullOrBlank()) { "LLM missing StepDefinitions.ts" }
        require(!files["feature.feature"].isNullOrBlank()) { "LLM missing feature.feature" }

        write(outDir.resolve("pages"), "BasePage.ts", files["BasePage.ts"])
        write(outDir.resolve("pages"), "PlatformPage.ts", files["PlatformPage.ts"])
        write(outDir.resolve("steps"), "StepDefinitions.ts", files["StepDefinitions.ts"])
        write(outDir.resolve("features"), "feature.feature", files["feature.feature"])

        println("✅ [Gen] Scripts written to ${outDir.absolutePath}")
        debugWrite(debugDir, "done.txt", "OK")
    }

    // -------------------- Local deterministic templates ---------------------

    private fun renderDeterministicFiles(
        plan: ActionPlan,
        bestByHint: Map<String, String>
    ): Map<String, String> {
        // Collect all hints (with selectors)  getters
        val allHints = bestByHint.keys
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        data class Entry(val hint: String, val prop: String, val selector: String, val rank: Int, val isInputLike: Boolean)

        fun selectorRank(sel: String): Int =
            when {
                sel.startsWith("id=") -> 4
                sel.startsWith("~") -> 3
                sel.startsWith("android=") -> 2
                else -> 1
            }

        fun isInputLike(h: String): Boolean {
            val s = h.lowercase()
            val isUser = Regex("\\b(user|username|email)\\b").containsMatchIn(s)
            val isLoginField = Regex("\\blogin\\s+(id|name|field|input)\\b").containsMatchIn(s)
            val isPass = Regex("\\b(pass|password|pwd)\\b").containsMatchIn(s) &&
                    Regex("\\b(field|input)\\b").containsMatchIn(s)
            val changePwd = Regex("\\b(change|reset|forgot)\\s+pass").containsMatchIn(s)
            return (isUser || isLoginField || isPass) && !changePwd
        }

        val entries = allHints.map { h ->
            val sel = bestByHint[h] ?: ""
            Entry(
                hint = h,
                prop = propName(h),
                selector = if (sel.isBlank()) fallbackSelectorFor(h) else sel,
                rank = selectorRank(if (sel.isBlank()) fallbackSelectorFor(h) else sel),
                isInputLike = isInputLike(h)
            )
        }

        val dedup = linkedMapOf<String, Entry>()
        for (e in entries) {
            val prev = dedup[e.prop]
            if (prev == null) {
                dedup[e.prop] = e
            } else {
                val better = when {
                    e.rank != prev.rank -> e.rank > prev.rank
                    e.isInputLike != prev.isInputLike -> e.isInputLike
                    else -> e.hint.length < prev.hint.length
                }
                if (better) dedup[e.prop] = e
            }
        }

        val props = dedup.values.toList()

        val basePage = buildString {
            appendLine("import { ChainablePromiseElement } from 'webdriverio';")
            appendLine()
            appendLine("export default abstract class BasePage {")
            for (p in props) {
                appendLine("  public abstract get ${p.prop}(): ChainablePromiseElement<WebdriverIO.Element>;")
            }
            appendLine("}")
        }

        val platformPage = buildString {
            appendLine("import BasePage from './BasePage';")
            appendLine()
            appendLine("class PlatformPage extends BasePage {")
            for (p in props) {
                appendLine("  public get ${p.prop}() { return $('${p.selector}'); }")
            }
            appendLine("}")
            appendLine()
            appendLine("export default new PlatformPage();")
        }

        val stepDefs = buildString {
            appendLine("import { Given, When, Then } from '@cucumber/cucumber';")
            appendLine("import { expect } from 'chai';")
            appendLine("import Page from '../pages/PlatformPage';")
            appendLine()
            appendLine("const toCamel = (s: string) => s")
            appendLine("  .replace(/[^a-zA-Z0-9]+/g, ' ')")
            appendLine("  .trim()")
            appendLine("  .split(/\\s+/)")
            appendLine("  .map((w,i)=> i===0 ? w.toLowerCase() : (w.charAt(0).toUpperCase()+w.slice(1).toLowerCase()))")
            appendLine("  .join('');")
            appendLine()
            appendLine("const toGetterKey = (hint: string) => {")
            appendLine("  const s = hint.toLowerCase();")
            appendLine("  const isUser = /(\\buser\\b|\\busername\\b|\\bemail\\b)/.test(s);")
            appendLine("  const isLoginField = /\\blogin\\s+(id|name|field|input)\\b/.test(s);")
            appendLine("  const isPass = /(\\bpass\\b|\\bpassword\\b|\\bpwd\\b)/.test(s) && /\\b(field|input)\\b/.test(s);")
            appendLine("  const changePwd = /\\b(change|reset|forgot)\\s+pass/.test(s);")
            appendLine("  if ((isUser || isLoginField) && !changePwd) return 'username';")
            appendLine("  if (isPass && !changePwd) return 'password';")
            appendLine("  return toCamel(hint);")
            appendLine("};")
            appendLine()
            appendLine("Given('the app is launched', async () => {")
            appendLine("  // Assume app is launched by the runner; no-op here")
            appendLine("});")
            appendLine()
            appendLine("When('I type {string} into {string}', async (value: string, hint: string) => {")
            appendLine("  const key = toGetterKey(hint);")
            appendLine("  const el: any = (Page as any)[key];")
            appendLine("  await el.waitForExist({ timeout: 30000 });")
            appendLine("  await el.setValue(value);")
            appendLine("});")
            appendLine()
            appendLine("When('I tap {string}', async (hint: string) => {")
            appendLine("  const key = toGetterKey(hint);")
            appendLine("  const el: any = (Page as any)[key];")
            appendLine("  await el.waitForExist({ timeout: 30000 });")
            appendLine("  await el.click();")
            appendLine("});")
            appendLine()
            appendLine("Then('I should see {string}', async (hint: string) => {")
            appendLine("  if (!hint) return; // skip empty assertions")
            appendLine("  const key = toGetterKey(hint);")
            appendLine("  const el: any = (Page as any)[key];")
            appendLine("  await el.waitForExist({ timeout: 45000 });")
            appendLine("  expect(await el.isExisting()).to.equal(true);")
            appendLine("});")
        }

        val feature = buildString {
            appendLine("Feature: ${plan.title.ifBlank { "Flow" }}")
            appendLine()
            appendLine("  Scenario: ${plan.title.ifBlank { "Run flow" }}")
            appendLine("    Given the app is launched")
            for (s in plan.steps.sortedBy { it.index }) {
                when (s.type) {
                    StepType.INPUT_TEXT ->
                        appendLine("    When I type \"${s.value ?: ""}\" into \"${s.targetHint ?: ""}\"")
                    StepType.TAP ->
                        appendLine("    And I tap \"${s.targetHint ?: ""}\"")
                    StepType.WAIT_TEXT ->
                        if (!s.targetHint.isNullOrBlank())
                            appendLine("    Then I should see \"${s.targetHint}\"")
                    else -> {}
                }
            }
        }

        return mapOf(
            "BasePage.ts" to basePage,
            "PlatformPage.ts" to platformPage,
            "StepDefinitions.ts" to stepDefs,
            "feature.feature" to feature
        )
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

    private fun propName(hint: String): String {
        val s = hint.lowercase()
        val changePwd = Regex("\\b(change|reset|forgot)\\s+pass").containsMatchIn(s)
        val usernameLike = Regex("\\b(user|username|email)\\b").containsMatchIn(s) ||
                Regex("\\blogin\\s+(id|name|field|input)\\b").containsMatchIn(s)
        val passwordLike = Regex("\\b(pass|password|pwd)\\b").containsMatchIn(s) &&
                Regex("\\b(field|input)\\b").containsMatchIn(s)
        return when {
            usernameLike && !changePwd -> "username"
            passwordLike && !changePwd -> "password"
            else -> toCamel(hint)
        }
    }

    private fun toCamel(s: String): String =
        s.replace(Regex("[^A-Za-z0-9]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .mapIndexed { i, w ->
                val ww = w.lowercase()
                if (i == 0) ww else ww.replaceFirstChar { it.uppercase() }
            }
            .joinToString("")

    private fun write(dir: File, name: String, content: String?) {
        require(!content.isNullOrBlank()) { "LLM missing $name" }
        dir.mkdirs()
        File(dir, name).writeText(content!!)
    }

    private fun debugWrite(folder: File, name: String, data: String) {
        try { File(folder, name).writeText(data) } catch (_: Throwable) {}
    }

    private fun extractJsonBlock(src: String): String {
        val fenced = Regex("```(?:json)?\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.groupValues?.get(1)
        if (fenced != null) return fenced.trim()
        val i = src.indexOf('{'); val j = src.lastIndexOf('}')
        return if (i != -1 && j > i) src.substring(i, j + 1).trim() else src.trim()
    }

    private fun parseOrCoerceToFileMap(blob: String): Map<String, String>? {
        return try {
            val n = mapper.readTree(blob)
            if (!n.isObject) return null
            val keys = listOf("BasePage.ts","PlatformPage.ts","StepDefinitions.ts","feature.feature")
            when {
                keys.all { n.has(it) && n.get(it).isTextual } ->
                    keys.associateWith { n.get(it).asText() }
                n.has("files") && n.get("files").isObject -> {
                    val f = n.get("files")
                    if (keys.all { f.has(it) && f.get(it).isTextual })
                        keys.associateWith { f.get(it).asText() } else null
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun retryFormatToFileMap(ollama: OllamaClient, blob: String): Map<String, String> {
        val sys = """
            Convert arbitrary content into a JSON object with exactly 4 string fields:
            "BasePage.ts","PlatformPage.ts","StepDefinitions.ts","feature.feature".
            Respond with JSON only. No markdown. No extra keys. The string values must be valid TypeScript (or Gherkin in feature.feature), not descriptions.
        """.trimIndent()
        val usr = """
            Convert the following to the required 4-key JSON; replace any placeholders with full code:
            ----
            $blob
            ----
        """.trimIndent()
        val raw = ollama.completeJsonBlocking(system = sys, user = usr)
        val json = extractJsonBlock(raw)
        return mapper.readValue(json)
    }
}
